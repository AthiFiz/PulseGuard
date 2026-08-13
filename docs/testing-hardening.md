# Testing, Reliability and Security Hardening

This document records what PulseGuard's tests actually cover, what was found and
fixed while hardening it, and — as importantly — what is *not* covered and why.

It describes the state after Task 11. Figures come from runs on the development
machine described in [Performance observations](#performance-observations); they
are not production benchmarks.

---

## 1. The testing decision: no automated infrastructure integration tests

**No automated integration testing against real infrastructure was introduced,
and none exists in this repository.** Specifically there is no Testcontainers, no
H2, no `@EmbeddedKafka`, no embedded SMTP server, no MySQL-backed automated test,
no integration-test Maven profile, and no automated use of a `pulseguard_test`
database.

Every automated test in all four projects runs against in-memory objects and
mocks, with no network, no database, and no broker. That is why the whole suite
runs on a laptop in a couple of minutes with nothing installed.

The cost of this choice is real and worth stating plainly: **nothing automated
proves that the Flyway schema matches the JPA entities, that the SQL in
`@Query` annotations parses under MySQL, or that a Kafka message survives a real
broker round trip.** Those properties are checked by hand, and the checks are
recorded in [Manual verification](#manual-verification) below.

Two things reduce that gap without a container:

- `spring.jpa.hibernate.ddl-auto=validate` in all three services. Any drift
  between the Flyway schema and the entity mappings fails startup loudly, so the
  manual run doubles as a schema check.
- The [shared event contract](#3-the-event-contract) — two applications testing
  against the same on-disk fixtures rather than trusting each other.

### What we test, and what we deliberately do not

| Layer | Covered automatically | Checked by hand | Not checked |
|---|---|---|---|
| Domain and state transitions | Yes — thresholds, recovery, duplicate-incident prevention | — | — |
| Authorization | Yes — role matrix, resource hiding, token rejection | Yes, through the UI | — |
| HTTP error mapping | Yes — 400/401/403/404/405/500 shapes | Yes | — |
| SQL correctness | No | Yes — every query exercised in the full-stack run | Query behaviour at production data volume |
| Flyway ↔ entity agreement | No | Yes — `ddl-auto=validate` on every startup | — |
| Kafka round trip | Contract shape only, both ends | Yes — publish, consume, replay | Broker failover, partition rebalance |
| Email delivery | Composition and retry logic | Yes — real SMTP conversation | Real provider behaviour (SES, TLS, auth) |
| Frontend | Yes — 78 tests over pages, auth, API client | Yes | Cross-browser |

### Coverage policy

Coverage is not a target. Tests were added where a defect would be silent and
expensive — state machines, authorization, parsing, money-like invariants such
as "one open incident per monitor". Tests were *not* added for getters, DTO
mapping, or configuration classes, and no test exists purely to raise a number.

Where a test was added during Task 11, it was first made to fail against the
unfixed code. Three of them are described below.

---

## 2. Security findings

### 2.1 Cloud metadata reachable over IPv6 — fixed

**Severity: high. Real, reachable, and fixed in this task.**

`DestinationPolicy` blocks the cloud metadata endpoints unconditionally, because
they hand out instance credentials to anything that can reach them. The blocklist
was compared as text:

```java
// before
private static final Set<String> ALWAYS_BLOCKED =
        Set.of("169.254.169.254", "fd00:ec2::254", "100.100.100.200");
...
if (ALWAYS_BLOCKED.contains(address.getHostAddress())) { ... }
```

Java renders `fd00:ec2::254` as `fd00:ec2:0:0:0:0:0:254`. The compressed literal
never matched the expanded form, so **AWS's IPv6 metadata endpoint was not
blocked** whenever `app.monitoring.allow-private-addresses` was true — the
development setting. The two IPv4 entries were unaffected and worked correctly.

The fix parses each literal through `InetAddress` at class-init time so both
sides of the comparison are spelled the same way:

```java
private static final Set<String> ALWAYS_BLOCKED = normalise(
        "169.254.169.254",  // AWS, Azure, DigitalOcean, OpenStack
        "fd00:ec2::254",    // AWS IPv6 metadata
        "100.100.100.200"); // Alibaba Cloud
```

A malformed literal now fails at startup rather than silently disabling a rule.

### 2.2 Open redirect after login — fixed

**Severity: moderate. Real, reachable, and fixed in this task.**

`ProtectedRoute` remembers the location it interrupted so that logging in resumes
the journey. That value comes from the address bar, so an attacker chooses it:

1. Send someone a link to `https://pulseguard.example/\evil.example`.
2. The route guard bounces them to `/login`, carrying `/\evil.example`.
3. They log in — on the genuine login form, with a genuine password.
4. The app navigates to `/\evil.example`, which browsers normalise to the
   protocol-relative `//evil.example`, landing on another host with the user's
   trust already established.

This was confirmed by temporarily reverting the fix and re-running the tests:

```
× refuses to leave the site for a path someone else chose
  → expected '/\evil.example' to be '/projects'
× refuses a protocol-relative destination
  → expected '//evil.example' to be '/projects'
```

The fix is `safeRedirectPath` in `frontend/src/auth/safeRedirect.ts`, which
reduces a remembered destination to a single-slash-rooted same-site path or
falls back to `/projects`. It is applied in `LoginPage`.

React Router fixed the same class of bug in 7.18 (GHSA-wrjc-x8rr-h8h6). Keeping
our own check is deliberate: it holds regardless of the router version, and the
rule belongs to the application.

### 2.3 Dependency advisories — accepted, with reasoning

`npm audit` reports two moderate advisories, both against `react-router`:

```
react-router  6.0.0 - 7.17.0
  Open redirect via backslash in <Link> and useNavigate   GHSA-wrjc-x8rr-h8h6
  Arbitrary Constructor Injection via deserializeErrors()  GHSA-337j-9hxr-rhxg
```

These are **not fixed by upgrading**, and `npm audit fix --force` makes things
worse: it would install `react-router-dom@6.30.1`, which is *older* than the
installed 6.30.4 and still inside the affected range. The real fix is the 7.x
line, which is a breaking change out of scope here.

Both are handled instead:

- The open redirect is closed in application code — see 2.2. It is the only
  place the application navigates to a path it did not construct itself; every
  other `Link` and `navigate` target is a literal or an interpolated numeric id.
- The `deserializeErrors()` advisory affects **SSR hydration**. PulseGuard's
  frontend is a client-rendered SPA built by Vite with no server-side rendering,
  so the affected code path is never executed.

**Accepted risk:** the dependency remains on the advisory list until the router
is upgraded to 7.x. That upgrade is a sensible follow-up, not a Task 11 change.

### 2.4 Reviewed and found sound

- **Secrets in logs.** No logging statement passes a password, token, secret,
  authorization header, credential, or a monitor URL. Searched across all three
  services.
- **Secrets in events.** The published event carries no URL, header, or response
  body, asserted on both the producer and consumer sides by property name.
- **Stack traces in responses.** The catch-all handler logs the cause and returns
  a fixed message. Jackson's and Spring Security's own messages, which name Java
  classes, are summarised rather than passed through.
- **Token rejection.** Expiry, foreign issuer, and wrong signing key are covered
  in `TokenServiceTest` against the real decoder; a rejected token at the HTTP
  boundary returns a JSON 401 that does not explain why.
- **Resource hiding.** A non-member receives 404, not 403, for both projects and
  monitors — so IDs cannot be enumerated by watching status codes.
- **No debug residue.** No `System.out.println`, `printStackTrace`, `console.log`,
  `TODO`, or `FIXME` anywhere in `src/`.

---

## 3. The event contract

The Monitor Worker and the Notification Service are separate Maven projects that
share no code — deliberately, because a producer and a consumer holding the same
class are only pretending to be decoupled.

That leaves nothing stopping one from drifting. So both test against the same
files:

```
docs/contracts/incident-opened-v1.json
docs/contracts/incident-resolved-v1.json
```

- The **producer** serialises real events and asserts the result has exactly the
  contract's property names, each with the contract's JSON type.
- The **consumer** parses the fixtures and asserts every value is interpreted
  correctly, and that its own record mirrors the contract field for field —
  without that last check, dropping a field would be invisible, since Jackson
  silently ignores what it has nowhere to put.
- Both assert the contract carries no sensitive property names.

If either side changes the shape, **its own build fails**. Evolving the contract
compatibly means adding an optional field to both; anything incompatible gets a
`…v2` topic rather than a quiet redefinition.

---

## 4. Reliability

### Failure model

| Failure | Behaviour | Why |
|---|---|---|
| Target API slow or down | Check recorded as FAILURE, threshold counts up | An incident needs `failureThreshold` consecutive failures, so one blip is not an outage |
| Kafka unreachable | Worker keeps monitoring; outbox accumulates | Events are written in the same transaction as the incident; publishing is a separate job |
| Consumer restarted mid-event | Event redelivered, recognised, ignored | Inbox keyed on `eventId`; offsets commit per record after the listener returns |
| Two consumers race the same event | One commits, the other's duplicate is recognised | See below |
| SMTP unreachable | Delivery stays PENDING and retries; service stays UP | Sending is not on the Kafka path at all |
| Address that never accepts mail | FAILED after 5 attempts | A finite ceiling makes the problem visible instead of consuming attempts forever |
| Monitor URL points inward | Blocked before the request is made | `DestinationPolicy`, judged on resolved addresses |

### The duplicate-event race — fixed

`NotificationEventProcessor` reads the inbox and then writes to it. Two consumers
holding the same event — a rebalance replaying an uncommitted offset, or a second
instance starting mid-flight — can both read "not present" before either commits.
One then loses on the unique constraint.

Previously that surfaced as a `DataIntegrityViolationException` escaping to the
container, a stack trace in the log, and a five-second retry that then succeeded.
Not data loss, but noise that looked like a fault.

It is now handled in `IncidentEventConsumer`, deliberately **outside** the
transaction — inside `process` the transaction is already doomed, and with JPA
the constraint may not surface until commit, which happens after that method
returns. The handling is narrow on purpose:

```java
try {
    notificationEventProcessor.process(event, record);
} catch (DataIntegrityViolationException ex) {
    if (!notificationEventProcessor.isAlreadyProcessed(event.eventId())) {
        throw ex;   // a real integrity fault, not a duplicate
    }
    log.info("Another consumer recorded this event first, ignoring duplicate: ...");
}
```

The re-check matters. The same exception type also covers genuine faults — a
subject line too long for its column, say — and swallowing those would drop
notifications silently. Four tests cover it, including the case that must still
throw.

### Health endpoints

All three services expose only `/actuator/health`. Boot's mail health indicator
is **disabled** in the Notification Service (`management.health.mail.enabled:
false`): it opens an SMTP connection per probe and reports the application DOWN
when that fails, which contradicts the design. An unreachable mail server is a
survivable condition — deliveries queue and retry — and reporting it as DOWN
would have Kubernetes restart a process that is doing its job correctly.

Verified: with the SMTP sink stopped and a delivery queued, `/actuator/health`
returned `{"status":"UP"}`.

### Transaction boundaries

Reviewed across all three services. `spring.jpa.open-in-view` is `false`
everywhere, so no transaction is silently held open for the duration of a
request. The consumer's unit of work is one short transaction that writes the
inbox row and the delivery rows together, and contacts no external system —
`ConsumerIsolationTest` asserts by reflection that neither the listener nor the
processor can even reach a `MailSender`.

---

## 5. Manual verification

Run on the development machine, 2026-08-13. Every figure below was observed;
nothing here is estimated.

### Stack

MySQL 8.0.31 · Kafka 4.3.1 (KRaft) · Control API 8080 · Monitor Worker 8081
(`MONITOR_ALLOW_PRIVATE_ADDRESSES=true`) · Notification Service 8082 · a
dependency-free SMTP sink on 127.0.0.1:1025 · a controllable target API on
127.0.0.1:9000 whose status code is switched by writing to a file.

All three services reported `{"status":"UP"}` before the run.

### Incident lifecycle

Monitor: 30s interval, 5s timeout, failure threshold 3.

| Time (UTC) | Event |
|---|---|
| 19:07:05 | First check succeeds, monitor UNKNOWN → UP |
| 19:08:07 | Target switched to HTTP 500 |
| 19:09:08 | Third consecutive failure — `Monitor status changed UP -> DOWN` |
| 19:09:13 | Outbox row written, incident 7 opened |
| 19:09:22 | `Outbox event published: eventType=INCIDENT_OPENED, key=20` |
| 19:09:29 | `Event consumed ... deliveries=1` |
| 19:09:31 | `Notification email sent: deliveryId=14, attempt=1` |
| 19:09:50 | Target restored to HTTP 200 |
| 19:10:09 | `Monitor status changed DOWN -> UP`, incident 7 resolved |
| 19:10:13 | Recovery event published |
| 19:10:21 | Recovery email sent |

The SMTP sink received exactly two messages:

```
Subject: [PulseGuard] Incident opened: Controlled Test API
Subject: [PulseGuard] Incident resolved: Controlled Test API
```

Detection took **61 seconds** from the target breaking to the DOWN transition —
consistent with a 30s interval and a threshold of 3. Incident to email was
**23 seconds**; recovery to email, **12 seconds**.

### Duplicate event

The exact `INCIDENT_OPENED` payload was republished to the topic with the same
`eventId` and key:

```
Event already processed, ignoring redelivery:
  eventId=b370d344-1ca9-4930-bea0-484d84afcaa5, eventType=INCIDENT_OPENED, offset=10
```

Emails received stayed at 2. `notification_deliveries` rows for that event stayed
at 1.

### SMTP outage

The sink was stopped and a second incident triggered.

```
attempt 1 failed, retrying at 19:14:22 — MailConnectException: Couldn't connect to host
attempt 2 failed, retrying at 19:14:52 — MailConnectException: Couldn't connect to host
Notification email sent: deliveryId=16, attempt=3
```

Throughout, `/actuator/health` reported UP. The delivery row moved PENDING →
SENT with `attempt_count = 3` once the sink returned. No event was reprocessed
and no email was lost or duplicated.

### Validation

Creating a monitor with a 10-second interval and no HTTP method was correctly
refused:

```json
{"status":400,"code":"VALIDATION_ERROR","message":"Request validation failed",
 "errors":[{"field":"httpMethod","message":"HTTP method is required"},
           {"field":"intervalSeconds","message":"Interval must be at least 30 seconds"}]}
```

---

## Performance observations

**Development machine only.** macOS 12.7.1, everything — MySQL, Kafka, all three
services, the target API and the mail sink — on one laptop. These numbers
describe this setup and are not a capacity estimate for anything else.

### Worker throughput, 101 monitors on a 30-second interval

Measured over a 5-minute steady-state window, after warm-up:

| Metric | Observed | Ideal |
|---|---|---|
| Checks completed | 959 | 1010 |
| Checks per monitor | 9.50 | 10 |
| Average gap between a monitor's checks | **31.60 s** | 30 s |
| Throughput | **191.8 checks/min** | 202 checks/min |
| Monitors overdue at sample time | 0 | 0 |
| Target response time | avg 6.4 ms (1–290 ms) | — |
| Worker RSS | 345 MB | — |

The worker keeps up: nothing is overdue, and the gap is tight (31.3–32.2 s across
758 intervals). The consistent ~5% drift is structural rather than a backlog —
`nextCheckAt` is computed from when a check *finishes*, and the poller wakes
every 5 seconds, so each cycle accumulates a little. The configured interval is
therefore a floor ("at least every 30 seconds"), not a period.

**A measurement caveat worth recording.** A first attempt reported 168.7
checks/min; that sample began while the 100 monitors were still being staggered
into their first check, so it measured warm-up. A second, 2-minute sample
reported 201.5 checks/min — too short, and swung ~5% by whether each monitor's
round landed inside the window. Only the 5-minute windowed figure above is
reliable, and it agrees with the independently-measured gap. The disagreement was
resolved rather than averaged.

### Query plans

`EXPLAIN` on the five hot queries, at development data volume (106 monitors,
~1,600 checks):

| Query | Plan | Index used |
|---|---|---|
| Monitor check history (paged) | `ref`, backward index scan | `idx_monitor_checks_monitor_checked_at` |
| Outbox — unpublished events | `ref`, index condition | `idx_outbox_events_pending` |
| Notification — due deliveries | `range`, index condition | `idx_notification_deliveries_due` |
| Worker — find due monitors | `ALL` + filesort | none chosen |
| Dashboard — project aggregate | `ALL` on checks, `eq_ref` to monitors | none chosen |

The last two scan, and at these table sizes that is the optimiser being right —
a full scan of 106 rows beats an index lookup. The indexes exist and appear in
`possible_keys`.

One finding is structural rather than volume-dependent, and worth carrying into
Stage 17. The due-monitor query is:

```sql
select ... from monitors
where current_status <> 'PAUSED' and next_check_at is not null and next_check_at <= ?
order by next_check_at asc limit 50
```

Forcing it onto `idx_monitors_status_next_check (current_status, next_check_at)`
still produces a filesort:

```
type: range   key: idx_monitors_status_next_check   Extra: Using index condition; Using filesort
```

`current_status <> 'PAUSED'` is an inequality on the index's *leading* column, so
the index cannot deliver rows already ordered by `next_check_at`. At 106 rows
this costs nothing. At tens of thousands it would sort the whole due set on every
poll. Two fixes, neither made here:

1. Index `next_check_at` alone — the predicate on it is already the selective one.
2. Rewrite the filter as `current_status in ('UP','DOWN','UNKNOWN')`, turning the
   leading column into an equality list the optimiser can use with the ordering.

**No migration was added.** The worker keeps up comfortably at present volumes,
this belongs with the multi-worker claiming work in Stage 17, and changing the
schema to pre-empt a problem not yet observed is the wrong trade.

### Not measured

Kafka consumer lag under sustained load, dashboard latency at large check
volumes, and behaviour above ~100 monitors. Those need the load-generation
harness that Stage 19 calls for.

---

## Issue classification

| Issue | Severity | Status |
|---|---|---|
| IPv6 cloud metadata endpoint not blocked | High | **Fixed** |
| Open redirect via remembered login destination | Moderate | **Fixed** |
| Duplicate-event race logged as an error | Low | **Fixed** |
| Frontend test suite flaky under load | Low | **Fixed** |
| `react-router` advisories | Moderate | Accepted — mitigated in application code; no upgrade path short of 7.x |
| Due-monitor query cannot use its index for ordering | Low | Documented for Stage 17 |
| No automated schema/SQL/broker coverage | Medium | Accepted by design; covered manually |

### Frontend flakiness

Two tests failed intermittently. The cause was not those two tests: Testing
Library's `asyncUtilTimeout` was at its 1000 ms default, while a screen like the
monitor detail view only finishes rendering after three *sequential* mocked round
trips. Any sufficiently deep page could lose the race, and under CPU load a
different test failed each run.

The fix is global, because the tightness was global: `asyncUtilTimeout` raised to
5000 ms in `src/test/setup.ts`. This does not slow anything down — `findBy*` and
`waitFor` poll and return the moment the element appears.

A second half emerged while stress-testing: 5000 ms equalled Vitest's default
`testTimeout`, so a genuinely stuck query was killed by the runner first and
reported a useless "test timed out" instead of Testing Library's "unable to find
role=…". `testTimeout` is now 15000 ms in `vite.config.ts`, comfortably above it.

Verified twice: when the fix was made, with 10 consecutive clean runs plus a run
under artificial CPU load; and again at the end of the task, with the newly added
tests included — 10/10 clean at 78 tests. The backend suites were re-run three
times each over the same period (monitor-worker 127, notification-service 71,
identical every time).

---

## Running the tests

```bash
# Backend — three independent Maven projects
cd backend/control-api         && ./mvnw clean verify
cd backend/monitor-worker      && ./mvnw clean verify
cd backend/notification-service && ./mvnw clean verify

# Frontend
cd frontend
npm run test        # vitest run
npx tsc --noEmit    # typecheck
npm run build
npm audit
```

Nothing above needs MySQL, Kafka, or an SMTP server running.

Current totals: **control-api 200 · monitor-worker 127 · notification-service 71
· frontend 78** — 476 tests.
