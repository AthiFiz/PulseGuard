# PulseGuard Architecture

This document describes the architecture as it stands at **Stage 10 —
Notification Service**, and the shape it is intended to grow into.

Anything marked **[NOT YET IMPLEMENTED]** does not exist in the codebase.

---

## Purpose

PulseGuard will eventually let users and development teams register HTTP/HTTPS
APIs and continuously monitor their availability, HTTP status, and response
time.

When a registered API fails repeatedly, PulseGuard will detect the failure, mark
the monitor as `DOWN`, open an incident, publish an event, and notify the
relevant users. When the API recovers, PulseGuard will detect the recovery, mark
the monitor `UP`, resolve the active incident, and send a recovery
notification.

Detection, `UP`/`DOWN` transitions and the reporting on top of them exist today,
and are usable from the browser. Incidents, events and notifications do not.

---

## Components

### React Frontend

The React application provides the entire user-facing interface. It is a
single-page application talking to exactly one backend, the Control API.

Implemented today:

- **authentication**: register, sign in, sign out, and session restore
- **projects**: list, create, rename, delete, and member management
- **monitors**: create, reconfigure, pause, resume, delete
- **monitoring views**: project dashboard, per-monitor statistics, and filtered,
  paginated check history
- **role-aware rendering**: a `VIEWER` is shown no action they cannot perform

Future responsibilities **[NOT YET IMPLEMENTED]**:

- response-time charts
- incident list and incident detail views
- live updates (every screen refreshes on navigation or an explicit *Refresh*)

### Control API

The Control API is a Spring Boot application handling synchronous, user-facing
REST operations. It is the only backend the frontend talks to.

Implemented today:

- `GET /actuator/health` and `GET /api/v1/system/info`
- configurable CORS for local frontend development
- **ownership of the PulseGuard relational schema**: Flyway migrations, JPA
  entities, and Spring Data repositories
- **stateless JWT authentication**: registration, login, and current user
- **project management**: project CRUD plus membership and role management
- **monitor configuration**: monitor CRUD, pause and resume
- **monitoring reads**: check history, monitor statistics, project dashboard
- **incident reads**: project incident history and incident detail

It **never writes an incident**, and never publishes an event. Opening and
resolving one is a statement about what a check observed, so only the worker may
make it. The Control API owns the `outbox_events` migration because it owns the
schema, but never reads or writes that table.

Future responsibilities **[NOT YET IMPLEMENTED]**:

- incident acknowledgement and assignment

### Monitor Worker

The Monitor Worker is a Spring Boot application deliberately kept **separate and
independently deployable** from the Control API.

Implemented today:

- `GET /actuator/health` and `GET /api/v1/system/info`
- **finding monitors that are due** for a check
- **executing HTTP GET checks** with a per-monitor timeout
- **measuring response time**
- **persisting every check** to `monitor_checks`
- **updating monitor state**: consecutive failures, and UP/DOWN transitions
- **opening and resolving incidents** from those transitions
- **writing incident events to a transactional outbox**, in that same transaction
- **publishing outbox events to Kafka** on a separate schedule

It connects to the same MySQL database as the Control API but maps only
`monitors`, `monitor_checks`, `incidents` and `outbox_events`, with
`project_id`, `monitor_id` and the check references as plain `Long` columns
rather than JPA associations —
the worker performs no project authorization, so the whole project-management
model would be dead weight.

**It runs no migrations.** Flyway lives entirely in the Control API; the worker
uses `ddl-auto=validate` and would fail to start if the schema did not match
what it expects.

**Kafka lives here and nowhere else.** The Control API has no producer and no
consumer, and the frontend knows nothing about the broker: an incident is
announced by whichever component observed it.

Future responsibilities **[NOT YET IMPLEMENTED]**:

- coordinating multiple worker replicas

---

## Three planes

| Application | Plane | Owns |
| --- | --- | --- |
| **Control API** | configuration and query | the schema, authentication, projects, monitor configuration, and every read the UI makes |
| **Monitor Worker** | monitoring and event production | executing checks, incident lifecycle, and publishing events through the outbox |
| **Notification Service** | event consumption and delivery | consuming events idempotently, resolving recipients, and sending email |

Each one can be stopped without stopping the others, and each degrades into a
smaller but still-correct system: no worker means no new checks; no notification
service means events wait on the topic; no SMTP means email queues.

---

## Why Control API and Monitor Worker Are Separate

The two applications have fundamentally different workload profiles.

The Control API serves synchronous user requests. Its load is driven by how many
people are using the UI, and it must stay responsive.

The Monitor Worker executes background monitoring work. Its load is driven by
how many monitors are registered and how frequently they are checked — entirely
independent of user activity.

Keeping them separate means each can eventually be scaled on its own. For
example:

```text
Control API
replicas: 2

Monitor Worker
replicas: 5
```

if monitoring demand grows without a matching growth in UI traffic. It also
means a heavy monitoring workload cannot degrade the responsiveness of the user
interface.

This is an architectural goal only. Kubernetes, replicas, and horizontal scaling
are **[NOT YET IMPLEMENTED]** and belong to a much later stage.

---

## Current Architecture (Stage 7)

```text
        ┌──────────────────────────────────────────┐
        │          React Frontend  :5173           │
        │            PRESENTATION PLANE            │
        │  Login · Projects · Members · Monitors   │
        │  Dashboard · Statistics · Check history  │
        │  Token in sessionStorage · no rules      │
        └──────────────────┬───────────────────────┘
                           │ HTTP + Bearer JWT
                           │ (CORS: FRONTEND_ORIGIN)
                           ▼
        ┌──────────────────────────────────────────┐
        │            Control API  :8080            │
        │              CONFIGURATION PLANE         │
        │  Authentication · Projects · Monitors    │
        │  Monitoring history · statistics · dashboard │
        │  Owns the Flyway migrations              │
        │  Authorises every request — the only     │
        │  enforcement point in the system         │
        └──────────────────┬───────────────────────┘
                           │ JDBC
                           ▼
                 ┌───────────────────┐
                 │       MySQL       │
                 │    pulseguard     │
                 └─────────▲─────────┘
                           │ JDBC
        ┌──────────────────┴───────────────────────┐
        │           Monitor Worker  :8081          │
        │              EXECUTION PLANE             │
        │  Polls for due monitors · runs checks    │
        │  Runs no migrations                      │
        └──────────────────┬───────────────────────┘
                           │ HTTP GET
                           ▼
                 ┌───────────────────┐
                 │  Monitored APIs   │
                 └───────────────────┘
```

The two backends never call each other. They meet in the database, which is what
lets them scale independently later: the Control API's load follows how many
people are using the UI, while the worker's follows how many monitors exist and
how often they are checked.

The frontend never touches MySQL and never talks to the worker. It sees the
system only through the Control API, so it is a rendering of that API's answers
and nothing more — every figure on a dashboard is one the backend computed.

---

## How a check happens

```text
MonitorPollingScheduler          every MONITOR_POLL_INTERVAL (default PT5S)
        │
        ▼
MonitorPollingService            status <> PAUSED
        │   due-monitor query      and next_check_at <= now
        │                          order by next_check_at, limit batch size
        ▼
  MonitorSnapshot                detached copy — no transaction held open
        │
        ▼
DestinationPolicy                resolve the host, apply the SSRF rules
        │                        blocked  → BLOCKED_ADDRESS, no request sent
        │                        no DNS   → DNS_ERROR
        ▼
HttpHealthChecker                GET, per-monitor timeout, no redirects,
        │                        body never read, monotonic timing
        ▼
HealthCheckResult                outcome, status, duration, error type
        │
        ▼
MonitorResultService             ONE short transaction:
        │                          insert monitor_checks
        │                          update monitors state + schedule
        │                          open or resolve the incident
        ▼                          insert the outbox event announcing it
```

**No database transaction is ever held open across the HTTP call.** The due
query finishes and detaches its rows, the request happens with nothing open, and
the result is written in its own short transaction afterwards. A monitored
endpoint that takes 30 seconds to answer therefore cannot pin a database
connection for 30 seconds.

The check row, the monitor's new state, the incident change and the outbox event
announcing it are written in **that same short transaction**, so a monitor can
never be committed as `DOWN` without the incident that explains it, nor an
incident without the event that announces it.

Monitors are processed **sequentially**, and one failing monitor is caught and
logged so the rest of the cycle continues. A failure of the polling query itself
is swallowed at the scheduler so the next tick retries — an escaping exception
would kill the scheduled task for the life of the process.

### Timing semantics

`checkedAt` is captured immediately **before** the request and is used for both
the stored check and the monitor's `lastCheckedAt`. The next run is scheduled as
`checkedAt + intervalSeconds`, not `now + intervalSeconds`, so the schedule does
not drift by the duration of every request.

The polling interval and a monitor's interval are different things. The scheduler
wakes every 5 seconds and asks which monitors are due; a monitor with a 60-second
interval is still checked once a minute.

### Races that are handled

| Race | Behaviour |
| --- | --- |
| Monitor paused mid-request | The check is stored, but the status stays `PAUSED` and `nextCheckAt` stays null — the pause is a deliberate instruction and wins |
| Monitor deleted mid-request | The result is discarded; inserting the check would violate the foreign key |
| Configuration changed mid-request | The result reflects the configuration at request time; the schedule is recalculated from the freshly re-read monitor |

### The incident lifecycle

An incident represents **one continuous outage**, not one failed check.

```text
      UNKNOWN / UP
            │
   consecutive failures reach
     the failure threshold
            │
            ▼
          DOWN ──────────► open an incident   (exactly one)
            │
            │  further failures: the same incident stays open,
            │  no second row is ever written
            │
     a successful check
            │
            ▼
           UP  ──────────► resolve that incident
```

A later outage opens a **new** incident. Resolved rows are never reopened or
reused, so the table is a record of distinct episodes rather than a mutable
status field.

Three rules are worth stating explicitly, because each one is a decision rather
than an accident:

| Rule | Why |
| --- | --- |
| Resolution follows a **successful check**, not a `DOWN → UP` transition | Pause and resume leave the monitor `UNKNOWN`, yet the outage it recorded is still the truth until something answers |
| **Pausing does not resolve** an incident | Choosing to stop looking is not evidence that the service recovered |
| **Resuming does not resolve** an incident | Resume only reschedules; it observes nothing |

So the full pause path is:

```text
DOWN + open incident
   │ pause          → PAUSED,  incident OPEN
   │ resume         → UNKNOWN, incident OPEN
   │ first success  → UP,      incident RESOLVED
```

An in-flight result that arrives after a pause is stored as a check but changes
neither the monitor's state nor its incident.

`openedAt` and `resolvedAt` are copied from the **checks' own timestamps**, not
from the clock at insert time, so the duration between them describes the
monitored service rather than the worker's scheduling.

If a monitor is somehow `DOWN` with no open incident — data from an interrupted
run, or an older environment — the next failed check opens one defensively and
logs a warning. That is a repair path, not a normal one.

### Events, and why Kafka is outside the transaction

An incident transition has to reach the outside world. The obvious way is to
save the incident and then send to Kafka — and it is wrong in a way that only
shows up in production.

```text
save incident  →  send to Kafka  →  commit
```

Three problems, in increasing order of how much they hurt:

| Problem | Consequence |
| --- | --- |
| The two are separate systems | A crash between them leaves the database and the topic disagreeing, with no way to tell which is right |
| A slow broker is inside the transaction | Monitoring slows down because a *message bus* is busy |
| An unreachable broker fails the transaction | A monitor cannot be marked `DOWN` because nobody could be told it was down |

The third is the unacceptable one. PulseGuard's core job is detecting outages;
making that depend on a broker inverts the priority.

So the event is written as an ordinary row in the same transaction:

```text
health check completes (no transaction open)
             │
             ▼
   MonitorResultService
             │
   ┌─────────┴──────────── one short MySQL transaction ───────────┐
   │  insert  monitor_checks                                       │
   │  update  monitors                                             │
   │  insert/update  incidents                                     │
   │  insert  outbox_events        ← the event, durable and local  │
   └─────────┬─────────────────────────────────────────────────────┘
             ▼
          COMMIT
             │
             ▼
    OutboxPublisher (separate schedule, separate thread)
             │
             ▼
           Kafka
             │
             ▼
   [Task 10: Notification Service]
```

Either the incident and its event both exist, or neither does. Delivery is a
separate concern with a separate failure mode, and
**`MonitorResultService` has no `KafkaTemplate`** — it could not contact a
broker if it tried.

There is deliberately **no distributed transaction**: no XA, no JTA, no
`ChainedKafkaTransactionManager`. The database transaction is the only atomic
unit, and Kafka is reached afterwards.

### The publisher

Every five seconds it asks for unpublished rows, oldest first, capped by a batch
size, and sends them one at a time.

Three properties are worth stating because each is a decision:

- **It waits for the broker's acknowledgement** before marking a row published.
  Marking on `send()` returning would record a delivery that may never have
  happened.
- **It stops at the first failure** rather than skipping past it. Events for one
  monitor are a sequence, and delivering the end of an outage whose beginning
  never arrived would tell a consumer something untrue. The cost is
  head-of-line blocking, documented below rather than hidden.
- **No transaction spans the send.** The pending rows come back detached, the
  network wait happens with no connection held, and the outcome is written in
  another short transaction.

Failure is recorded, not swallowed: `attempt_count` increments,
`last_attempt_at` and a bounded `last_error` are stored, and the row stays
pending. Nothing is ever dropped and nothing gives up after N attempts — the
payload is generated entirely by PulseGuard, so a permanent failure means
something a human should see.

### When Kafka is unavailable

```text
Kafka unavailable
        ↓
checks keep running · incidents keep opening and resolving
        ↓
outbox rows accumulate as pending
        ↓
broker returns
        ↓
the publisher drains the backlog, oldest first
```

This is the property the whole design exists for, and it was verified by
stopping the broker mid-outage: monitoring continued, an incident opened with no
broker running, the event stayed pending with its attempt recorded, and it
published automatically once Kafka came back.

The worker also **starts** with no broker reachable. Topic creation is
best-effort; failing to create a topic is not a reason to refuse to monitor.

### Delivery semantics

**At-least-once.** A consumer may see the same event twice:

```text
broker acknowledges the record
        ↓
the worker crashes before published_at is committed
        ↓
the event is sent again after restart
```

Every event carries a globally unique `eventId`, stable across republication, so
a consumer can recognise the repeat. Task 10's consumer owns that deduplication.

Producer idempotence is enabled and is a **different** mechanism: it stops the
producer's own retries duplicating a record within one session. It says nothing
about the window above, and the two together still do not make the pipeline
exactly-once.

### Not implemented at this stage

**No notifications, and no consumer.** Incident events reach Kafka and stop
there — nothing reads the topic. Task 10 introduces the Notification Service,
the first real consumer, and with it the email that finally tells someone.

There is no acknowledgement, assignment, severity or comment thread — all of
those need a person acting on an incident, which the product does not yet offer.

**No distributed locking.** Exactly one worker instance is assumed — two workers
against the same database would both see the same due monitors and check
everything twice. The "at most one open incident per monitor" rule is enforced by
that single writer plus an application-level check, **not** by a database
constraint; a second worker could race two open incidents into existence.
Coordination (`SELECT … FOR UPDATE SKIP LOCKED`) belongs to the Kubernetes
scaling stage, and Task 08 does not pretend to solve it.

---

## Write path and read path

Monitoring data has exactly one writer and one reader, and they are different
applications:

```text
Monitored APIs
      ▲
      │ HTTP GET
      │
Monitor Worker                    WRITE PATH
      │
      │ inserts monitor_checks
      │ updates monitors.current_status
      ▼
   MySQL
      │
      │ aggregate queries
      ▼
Control API                       READ PATH
      │
      ├── GET /monitors/{id}/checks       history
      ├── GET /monitors/{id}/statistics   uptime, response times
      └── GET /projects/{id}/dashboard    project snapshot
      │
      ▼
Future Frontend                   [NOT YET IMPLEMENTED]
```

The separation is deliberate and worth stating plainly: **the worker never
serves a request, and the Control API never performs a check.** Neither can slow
the other down except through the database, and each can be scaled for its own
workload — the API's load follows how many people are looking at it, the
worker's follows how many monitors exist.

### Reading without loading everything

A single monitor on a 30-second interval produces about a million rows a year.
Nothing in the read path ever loads a check collection into Java to reduce it:

| Endpoint | How it reads |
| --- | --- |
| Check history | Paginated, capped at 100 rows, always `ORDER BY checked_at DESC` |
| Monitor statistics | One `COUNT`/`SUM`/`AVG`/`MIN`/`MAX` aggregate in MySQL |
| Dashboard | Exactly three queries, regardless of monitor count |

The dashboard's three queries are a grouped status count, one project-wide check
aggregate joined through `monitors.project_id`, and the recent failures. There is
no loop issuing a query per monitor — a project with 500 monitors would otherwise
cost 1500 round trips.

All of it leans on the `(monitor_id, checked_at)` index created in Task 02, which
serves both the history ordering and the range filters.

### What the numbers mean

**Uptime is check-based**: successful checks divided by total checks. It is not
duration-based availability, which would need incidents to know how long an
outage actually lasted. The README says so explicitly, because "99.65% uptime"
invites being read as an SLA figure.

**Project uptime aggregates checks, never averages percentages.** A monitor with
1000 successful checks and one with a single failure is 99.90% by check count;
averaging the two percentages gives 50%, which describes nothing real.

**`null` is not zero.** No checks means unknown availability, not zero
availability — so `uptimePercentage` is null rather than `0`. The same applies to
response times when no check in range recorded a duration.

**Monitor status counts are current state**, read from
`monitors.current_status`, and do not move when the dashboard's time window
changes. Only the check figures and recent failures describe the window.

### Growth

`monitor_checks` is append-only and grows without bound — one row per check,
forever, for every monitor. Nothing prunes it.

Future considerations, none implemented: retention limits, partitioning by time,
pre-aggregated summary tables for long ranges, a time-series store, or archival
to cold storage. Pagination and database-side aggregation keep the current
queries honest, but they do not stop the table growing.

---

## Monitor status: who may assert what

`UNKNOWN` and `PAUSED` are statements of intent and can be set by a user, through
creation, pause, and resume. `UP` and `DOWN` are conclusions drawn from
observation and are written **only** by the Monitor Worker — the Control API
exposes `pause` and `resume` rather than a general "set status" endpoint, so no
client can declare a monitor healthy.

That is also why resume returns a monitor to `UNKNOWN` rather than `UP`: at that
moment no check has run, so its health is genuinely unknown.

```text
UNKNOWN ──── successful check ────► UP ◄──── successful check ──── DOWN
   │                                 │                              ▲
   │                        failures reach the                      │
   └── failure below threshold       threshold ─────────────────────┘
       keeps the current status
```

A failure below the threshold preserves the existing status, so a single blip
does not flip a healthy monitor. A success resets the failure counter from any
state. **Neither transition produces an incident or a notification yet.**

---

## SSRF protection

The worker sends HTTP requests to URLs supplied by its users, which makes it a
potential server-side request forgery tool: anyone who can create a monitor
could otherwise point it at internal services they cannot reach themselves and
learn something from the result.

### What is implemented

- **Resolution at execution time.** `DestinationPolicy` runs immediately before
  every request, not when the monitor was saved — the answer can change.
- **Judgement on resolved addresses, never on the host string.** Blocking the
  literal text `localhost` would be defeated by `127.0.0.1`, `[::1]`,
  `2130706433`, or any name pointed at a loopback address. **Every** address a
  name resolves to is examined, so a host returning one public and one private
  address is refused.
- **Blocked by default:** loopback, wildcard, link-local, IPv4 private ranges
  (10/8, 172.16/12, 192.168/16), IPv6 unique-local (fc00::/7), carrier-grade NAT
  (100.64/10), and multicast.
- **Always blocked**, even with the development override on: cloud metadata
  endpoints — `169.254.169.254`, `fd00:ec2::254`, `100.100.100.200` — because
  they hand out instance credentials to anything that can reach them.
- **Redirects are not followed.** A permitted public URL that redirects into
  private space would otherwise bypass the policy entirely, since only the first
  hop is checked. A 3xx is simply reported as the status it is.
- **Per-monitor timeouts** on both connect and read, so a hostile endpoint cannot
  hold the worker open.
- **The response body is never read**, so a large or slow body cannot exhaust
  memory. Only the status code and elapsed time are used.
- **Errors are bounded and generic.** Exception messages, which may echo parts of
  the URL, are not stored; messages are truncated to the column length.

### The development override

`MONITOR_ALLOW_PRIVATE_ADDRESSES=true` relaxes the private-network rules so a
developer can monitor something on their own machine. It is **false by default**,
and cloud metadata and multicast stay blocked regardless.

### Known limitations — this is not complete SSRF protection

- **DNS rebinding is not fully prevented.** The policy resolves the host and then
  the HTTP client resolves it again when it opens the socket. A name that answers
  with a public address for the first lookup and a private one for the second
  would slip through. Closing this properly means pinning the validated address
  through to the socket, which is deliberately out of scope here.
- Only the first hop is validated, which is safe only because redirects are
  disabled.
- There is no allow-list mode, no egress proxy, and no per-project destination
  policy.
- IPv4-mapped IPv6 forms and unusual literal encodings rely on the JDK's parsing
  being canonical. This is now pinned down by tests rather than assumed:
  `::ffff:127.0.0.1` normalises to an `Inet4Address` and is blocked; `2130706433`
  and `127.1` both resolve to loopback and are blocked; `0177.0.0.1` is *not*
  read as octal by Java and resolves to the public 177.0.0.1, which is documented
  rather than treated as a bypass.

One defect in this area was found and fixed during the hardening stage: the
cloud-metadata blocklist compared address text, and Java expands
`fd00:ec2::254` to `fd00:ec2:0:0:0:0:0:254`, so **AWS's IPv6 metadata endpoint
was not blocked** when the development override was enabled. Literals are now
normalised through `InetAddress` before comparison. See
[docs/testing-hardening.md](testing-hardening.md).

The remaining limitations above belong to a later stage.

---

## Deployment view: Docker Compose (Stage 12)

Everything above describes the *logical* architecture, which containerisation
did not change. This section describes how those same components are packaged
and wired when the whole system runs from `docker compose up -d --build`. The
operational detail — ports, environment variables, troubleshooting — lives in
[docs/docker.md](docker.md).

```text
                                Browser
                                   │
                                   │  http://localhost:5173
                                   ▼
                         ┌──────────────────┐
                         │ frontend (nginx) │
                         │ · static SPA     │
                         │ · try_files → SPA│
                         │ · /api/* proxy ──┼──┐
                         └──────────────────┘  │  control-api:8080
                                               ▼
                                    ┌──────────────────┐
                                    │   control-api    │
                                    │ sole Flyway owner│
                                    └────────┬─────────┘
                                             │
                                             ▼
                                    ┌──────────────────┐
                    ┌──────────────▶│      mysql       │◀──────────────┐
                    │               │ volume mysql_data│               │
                    │               └──────────────────┘               │
                    │                                                  │
         ┌──────────┴──────────┐                    ┌──────────────────┴───┐
         │   monitor-worker    │                    │ notification-service │
         │  checks, incidents  │                    │    Kafka consumer    │
         │   outbox producer   │                    │    email delivery    │
         └──────────┬──────────┘                    └───────┬──────────────┘
                    │ publishes                    consumes │
                    │           ┌──────────────────┐        │
                    └──────────▶│      kafka       │───────▶┘
                                │ single-node KRaft│
                                │ volume kafka_data│
                                └────────┬─────────┘
                                         │ observed by
                                         ▼
                                ┌──────────────────┐
                                │ kafka-ui (tools) │
                                └──────────────────┘

      notification-service ──SMTP──▶ mailpit ──▶ web inbox :8025
```

### What containerisation did *not* change

No service was split, merged, or given a new responsibility. The Control API is
still the configuration and query plane and the only holder of Flyway; the
Monitor Worker still owns checks, the incident lifecycle and the outbox; the
Notification Service still consumes from Kafka and delivers email on its own
schedule; the frontend still talks only to the Control API. The two backends
still never call each other — they meet in the database.

### Three things Compose has to get right

**Flyway ordering.** On an empty volume the schema does not exist, and both the
worker and the notification service start with `ddl-auto=validate` — they
compare their JPA mappings against the live schema and refuse to start if it is
missing. Both therefore wait for `control-api` to report *healthy*, not merely
running. They do not call it; they wait because it is the service that creates
the tables. Ordering is expressed entirely with Compose health conditions —
there are no sleeps anywhere.

**Two Kafka listeners.** A Kafka client connects, is told where the broker
really is, and reconnects there — so the advertised address has to be correct
*from the client's position*. Containers are told `kafka:29092`; processes on
the host are told `localhost:19092`. One listener could not serve both, because
`localhost` inside a container means that container.

**Same-origin browser traffic.** The browser cannot resolve Docker service
names, so the API location could not simply be pointed at `control-api:8080`.
The frontend image is built with an empty `VITE_API_BASE_URL`, which makes the
bundle request `/api/v1/...` with no host, and nginx proxies those to the
Control API inside the network. The alternative — a cross-origin call direct to
the published port — would have meant widening CORS to make a development
convenience work. CORS configuration is unchanged.

### Where the SSRF policy is deliberately relaxed

Every address inside a Compose network is private, so `DestinationPolicy` as
configured for a deployed PulseGuard would refuse to monitor *anything* in the
stack. Compose therefore sets `MONITOR_ALLOW_PRIVATE_ADDRESSES=true` for the
worker. This is the same development override described under
[SSRF protection](#the-development-override); it is local-Compose-only and must
never be a deployed default. The unconditional cloud-metadata blocklist is not
affected by it.

### Development infrastructure, not the target architecture

The infrastructure choices here exist to make one laptop reproducible, and are
replaced later:

| Compose (development) | Later (AWS) |
| --- | --- |
| MySQL container, `mysql_data` volume | Amazon RDS for MySQL |
| Single-node KRaft Kafka, plaintext, no auth | Managed, replicated Kafka |
| Mailpit | Production SMTP / Amazon SES |
| Kafka UI | No production role |
| One replica of each service | Multiple replicas, horizontally scaled |
| Development credentials in `compose.yaml` | Secret store |
| Health-check timings sized for a slow laptop | Tightened for real hardware |

Docker is additive. The existing workflow — local MySQL, local Kafka, Spring
Boot from the IDE, `npm run dev` — is unchanged, and remains the faster loop for
day-to-day development and the only way the test suites are run.

---

## Future Architecture

```text
                           ┌────────────────────┐
                           │   React Frontend   │
                           └─────────┬──────────┘
                                     │
                                     ▼
                           ┌────────────────────┐
                           │    Control API     │
                           └─────────┬──────────┘
                                     │
                                     ▼
                           ┌────────────────────┐
                           │      MySQL         │
                           │   [NOT YET ADDED]  │
                           └────────────────────┘


                           ┌────────────────────┐
                           │   Monitor Worker   │
                           └─────────┬──────────┘
                                     │
                         ┌───────────┴───────────┐
                         ▼                       ▼
                ┌────────────────┐      ┌────────────────┐
                │     MySQL      │      │     Kafka      │
                │ [NOT YET ADDED]│      │ [NOT YET ADDED]│
                └────────────────┘      └───────┬────────┘
                                                │
                                                ▼
                                      ┌────────────────────┐
                                      │ Notification       │
                                      │ Service            │
                                      │ [NOT YET ADDED]    │
                                      └────────────────────┘
```
---

## Consuming events: the inbox pattern

The producer side (Task 09) guarantees **at-least-once** publication. The
consumer side has to be built for that, not around it.

```text
                    Kafka incident event
                             │
                             ▼
                    is this eventId known?
                    ┌────────┴────────┐
                   no                yes
                    │                 │
                    ▼                 ▼
        ┌───────────────────────┐   do nothing
        │ one MySQL transaction │   return successfully
        │  insert consumed_event│   (the offset still commits)
        │  insert deliveries    │
        └───────────┬───────────┘
                    ▼
                  commit
```

This is the inbox pattern — the mirror of the outbox on the producing side.
Where the outbox makes "the event will be sent" atomic with the state change,
the inbox makes "the event will be acted on once" durable across restarts.

Two guards, at different levels:

| Guard | Catches |
| --- | --- |
| `existsByEventId` before writing | The ordinary redelivery, cheaply |
| `UNIQUE(event_id)` in the database | The race the read-before-write cannot: two consumers, or a retry mid-transaction |
| `UNIQUE(event_id, recipient_email, channel)` | A bug that processed one event twice queueing one person two identical emails |

**Kafka's offset is deliberately not the identity.** A redelivered event arrives
at a different offset; identity belongs to the event, not to its position in a
log. The offset is stored for tracing and nothing else — verified by replaying a
real message, consumed at offset 4 and ignored at offset 5.

### Why the listener does not send email

```text
Kafka consumption  →  DB commit  →  ... later ...  →  email
```

If the listener sent the mail itself, an unreachable SMTP host would throw, the
offset would never commit, and Kafka would redeliver the same incident until the
mail server recovered — a delivery problem escalated into an event-processing
problem, with a growing consumer lag to show for it.

By committing first and sending later, an SMTP outage is contained: the event is
dealt with, the delivery is queued, and only the last hop is waiting. This is why
`NotificationEventProcessor` has no `JavaMailSender` — enforced by a test, so it
stays that way.

### Delivery, and what SMTP can and cannot promise

```text
PENDING ──── mail server accepts ────► SENT
   │
   │ attempt fails
   ▼
PENDING (attempt_count + 1, retry after the configured delay)
   │
   │ attempts exhausted
   ▼
FAILED  ← kept for inspection, never retried automatically
```

Deliveries are independent, so unlike the outbox publisher this loop does **not**
stop at the first failure: there is no ordering between recipients, and one bad
address must not delay everyone else. No transaction is held across the SMTP
conversation.

Two guarantees, deliberately not conflated:

| | Guarantee |
| --- | --- |
| Kafka ingestion | **idempotent** — one `eventId`, processed once |
| Email delivery | **at-least-once / best effort** |

The email window cannot be closed:

```text
the mail server accepts the message
        ↓
the service crashes before SENT is committed
        ↓
the delivery is still PENDING and is retried
        ↓
the recipient may receive it twice
```

Sending an email is an external side effect with no rollback. No amount of
database work makes it exactly-once, and PulseGuard does not claim otherwise.


---

## Security

### Stateless JWT

Authentication is a signed HS256 access token, validated by Spring Security's
OAuth2 resource server — signature, expiry, and issuer. No custom JWT filter is
written and no server-side session is created, so any instance can serve any
request. This matters later: the Control API is meant to run as several
Kubernetes replicas behind a load balancer, and sticky sessions would undermine
that.

Login runs through the standard `AuthenticationManager` →
`DaoAuthenticationProvider` → `UserDetailsService` chain. Passwords are stored
with a delegating encoder (bcrypt by default), whose `{id}` prefix leaves room
to migrate algorithms later without invalidating existing hashes.

The `User` JPA entity does **not** implement `UserDetails`. A separate
`AuthenticatedUser` record is the security principal, keeping the persistence
model free of framework interfaces.

### What the token carries — and what it must not

```json
{ "iss": "...", "sub": "15", "email": "...", "system_role": "USER", "iat": 0, "exp": 0 }
```

Only stable, platform-wide identity. **Project roles are deliberately excluded.**
A membership can be granted or revoked at any moment, but a token stays valid
until it expires; a token asserting `PROJECT_ADMIN` would keep granting that
power after it had been taken away. So `system_role` becomes a Spring Security
authority, while project roles are read from `project_members` at the moment
they are needed.

### Two layers of authorization

| Layer          | Decides                          | Enforced by                    |
| -------------- | -------------------------------- | ------------------------------ |
| Filter chain   | authenticated vs anonymous       | `SecurityConfig` URL rules     |
| Service layer  | which project, and what role     | `ProjectAccessService`         |

Everything not explicitly public is `authenticated()` by default, so a new
endpoint is never accidentally left open. Project-level checks live in one
service rather than being repeated per controller.

Non-members get `404` instead of `403` when reading a project, so iterating over
ids reveals nothing about projects belonging to other people.

### CSRF and CORS

CSRF protection is disabled, which is safe **only because** this API has no
ambient credentials for a browser to attach automatically: it issues no
authentication cookie and never authenticates from one. The bearer token must be
set explicitly by JavaScript that is already bound by the same-origin policy, so
a cross-site form post simply arrives unauthenticated. Introducing cookie
authentication later would require re-enabling CSRF.

CORS keeps the configurable origin list from Stage 1 (`FRONTEND_ORIGIN`) and is
applied through Spring Security. The wildcard origin is never used.

### Where the browser keeps the token — and what that costs

The frontend stores the access token in `sessionStorage`. That is a deliberate
choice with a real limitation, so it is worth stating plainly.

**What it buys.** The token never leaves the tab that obtained it, is gone when
the tab closes, and is never attached automatically by the browser — which is
exactly what makes disabling CSRF safe. A cookie would be sent on every
cross-site request whether the application wanted it or not.

**What it costs.** `sessionStorage` is readable by any JavaScript running on the
page. Script injected into the frontend could read the token and use it until it
expires. An `HttpOnly` cookie would be immune to that, at the price of needing
CSRF protection and a same-site story.

The mitigations that exist today are modest and honest about their scope: React
escapes rendered content by default, the application never uses
`dangerouslySetInnerHTML`, tokens live for one hour, and the token carries only
platform identity — never project roles — so a stolen token still cannot claim a
role its holder was never granted. There is no refresh token, so a stolen token
cannot be renewed either.

A production deployment would revisit this: short-lived tokens in memory with a
refresh cookie, or an `HttpOnly` cookie with CSRF tokens. Both need endpoints
that do not exist yet.

### Resuming an interrupted journey

`ProtectedRoute` remembers the location it turned away, so that logging in lands
where the user was going rather than dumping them on the project list. That
remembered value comes from the address bar, which makes it attacker-chosen: a
link to `https://pulseguard.example/\evil.example` would otherwise send the user
off-site immediately after a genuine login, with their trust already established
by the real login form.

Destinations are therefore reduced to a same-site path by `safeRedirectPath`
before being navigated to — a single-slash-rooted path, or nothing. React Router
fixed the same class of bug in 7.18, but the check stays in application code: it
holds regardless of the router version, and "never leave the site on the strength
of a URL somebody else handed the user" is this application's rule, not a
dependency's.

### Authorisation is not in the frontend

The UI hides what a `VIEWER` cannot do, and refuses management URLs typed
directly. **None of that is a security control.** It is a courtesy — anything
shipped to a browser can be edited in a browser.

Every request is authorised again by the Control API through
`ProjectAccessService`, and that is the only decision that counts. This was
verified rather than assumed: a `VIEWER` who reached the monitor edit form before
the guard was added could fill it in, and the save came back
`403 You do not have permission to perform this action`.

---

## Database

**MySQL 8** is the relational database for PulseGuard. Production will
eventually use **Amazon RDS for MySQL**; local development uses a locally
installed MySQL server.

### Schema ownership

The **Control API owns the schema**. All Flyway migrations and JPA entities live
in `control-api`, and no other application defines or migrates tables. Because
the two backend applications are independent Maven projects with no shared
module, putting migrations in both would mean duplicating them — so a single
owner is the only sane arrangement. When the Monitor Worker eventually needs
database access, it will connect to the same schema without owning it.

### Flyway, not Hibernate

Flyway is the sole authority on schema structure. It runs automatically on
Control API startup and applies versioned SQL migrations from
`classpath:db/migration`.

Hibernate runs with `ddl-auto=validate`: it never creates, drops, or alters
anything, and it fails startup if the entity mappings disagree with the migrated
schema. That disagreement is caught at boot rather than at the first query.

### Tables

```text
users             accounts; unique email
projects          monitor groupings; created_by -> users
project_members   user/project membership; unique (project_id, user_id)
monitors          monitored endpoints, their configuration and current state
monitor_checks    the result of each individual check; written by the worker,
                  read by the Control API's reporting endpoints
outbox_events     one row per event awaiting delivery to Kafka; written and
                  published by the worker. No foreign keys: an event records
                  something that already happened and must stay publishable
                  even if the monitor it describes is deleted a moment later
incidents         one row per continuous outage; written by the worker,
                  read by the Control API. monitor_id CASCADEs, and the two
                  check references are SET NULL so losing a check cannot
                  block a delete or erase the outage record
```

`incidents` also carries a CHECK constraint tying status to resolution: an
`OPEN` row must have no `resolved_at`, and a `RESOLVED` row must have one. The
database refuses a half-finished incident even if application code ever tried to
write one.

Deleting a monitor removes its checks **and its incidents** through those
cascades; deleting a project removes its monitors and everything below them.
That is a deliberate trade: outage history is scoped to the monitor it describes,
and PulseGuard has no soft delete.

All tables are InnoDB / utf8mb4, use `snake_case` naming, and use
`BIGINT AUTO_INCREMENT` primary keys. Timestamps are `DATETIME(6)` and are
handled as UTC `Instant` values in Java. Enums are stored as `VARCHAR` strings
rather than MySQL `ENUM` columns, so adding a value is an application change
rather than a schema migration.

### Testing

There is deliberately **no database integration-test scope** in this project.
The automated tests are fast and need no infrastructure. Schema correctness is
verified by starting the Control API against MySQL, where Flyway migration and
Hibernate validation both run.

---

## Known limitations of the frontend

Stated here so they are not mistaken for oversights.

- **No live updates.** Screens load when you navigate to them and reload when you
  press *Refresh*. A monitor that goes down while you are looking at the page
  turns red on the next load, not the moment it happens. Polling or a push
  channel is a later concern.
- **No charts.** Response times are shown as numbers — average, fastest, slowest.
  Drawing a series would mean a charting dependency and an endpoint that returns
  time buckets; neither exists yet.
- **Check history is paginated, not searchable.** You can filter by outcome and
  date range because the API supports exactly that, and nothing more.
- **Statistics cover all recorded history.** The per-monitor range parameters
  exist in the API but the UI does not yet expose them; only the project
  dashboard's fixed 24-hour window is shown.
- **No incidents anywhere.** Nothing in the UI mentions incidents, because the
  backend has none.
- **Members are added by exact email.** There is no user search — the API offers
  none, and adding one would let anybody enumerate registered accounts.
- **Token in `sessionStorage`.** Discussed under *Security* above; it is a
  known trade-off, not an accident.
- **The router carries two moderate advisories.** `react-router` 6.30.4 is
  reported by `npm audit` for an open-redirect via a backslash in a `<Link>`
  target, and for `deserializeErrors()` during SSR hydration. The second does not
  apply — this application does no server-side rendering. The first is only
  reachable through a link target this application never builds: every route is a
  literal string, and no user input becomes a path. The only fix offered is
  React Router 7, which this stage deliberately does not adopt.

---

## Known limitations of notifications

- **No preferences.** Every enabled member of a project is emailed about every
  incident in it. There is no per-user setting, no digest and no quiet hours.
- **Email may duplicate.** The crash window above is unavoidable; the ingestion
  side is idempotent, the sending side is best-effort.
- **Finite attempts, and no manual retry.** A delivery that exhausts its
  attempts is left `FAILED` for inspection. There is no API to retry it — a
  deliberate omission rather than a missing feature.
- **A poison record can block a partition.** The consumer retries indefinitely
  rather than discarding events, so a permanently unreadable payload would stall
  its partition. There is no dead-letter topic yet; adding one — with
  `ErrorHandlingDeserializer` and a `DeadLetterPublishingRecoverer` — is the
  natural Task 11 hardening step.
- **Email only.** No Slack, SMS, webhooks or push, and no HTML.
- **The schema is shared.** The Notification Service reads `users` and
  `project_members` directly over JDBC rather than calling the Control API. That
  keeps the dependency narrow and read-only, but it is a shared database between
  services, with the coupling that implies.
- **No automated infrastructure tests.** The whole service is covered by unit
  tests with mocks; Kafka, MySQL and SMTP behaviour was verified by hand.

---

## Known limitations of event streaming

- **At-least-once delivery.** A consumer may see an event twice; `eventId` is
  what makes deduplication possible, and no consumer implements it until
  Task 10.
- **One worker, one publisher.** Pending rows are claimed by nothing — a second
  worker would publish the same events again. Distributed outbox claiming
  (`SELECT … FOR UPDATE SKIP LOCKED`) belongs with the scaling stage.
- **Head-of-line blocking.** The publisher stops at the first failure to keep
  ordering, so one permanently failing row would hold up everything behind it.
  Chosen deliberately over reordering; there is no dead-letter table, no
  quarantine and no skip-after-N-attempts.
- **Outbox rows are kept forever.** Published events are never deleted. Useful
  for inspection now, but a retention policy will eventually be needed.
- **No Schema Registry.** Compatibility rests on `schemaVersion` inside the
  payload and the `.v1` topic suffix.
- **No Kafka authentication or TLS.** The local broker is unauthenticated;
  securing it is a deployment concern.
- **No automated broker test.** The publisher is covered by unit tests with a
  mocked `KafkaTemplate`. Broker-backed integration testing is deferred with the
  rest of that scope, so the build needs no Kafka, Docker or Testcontainers.

---

## Known limitations of incident management

Stated here so they are not mistaken for oversights.

- **One worker only.** The one-open-incident-per-monitor rule is enforced by
  application logic in a single writer, not by a unique constraint. Two workers
  could open two incidents for the same outage. Hardening this belongs with
  multi-worker coordination.
- **No events, no notifications.** An incident opens and nobody is told. The
  observable result is a database row and a screen.
- **No acknowledgement, assignment, severity or comments.** All of them need a
  person acting on an incident; the product offers no such action yet.
- **No manual create or resolve.** Deliberate: an incident asserts something
  about the monitored service, and only a check can observe that.
- **Uptime still comes from check counts,** not incident durations. The
  durations are now recorded and correct, but nothing uses them for availability
  yet.
- **Deleting a monitor deletes its incident history**, through the same cascade
  that already removed its checks.
- **The UI does not update on its own.** An incident opening appears on the next
  navigation or *Refresh*, like every other screen.
- **No automated database tests.** The lifecycle is covered by fast interaction
  tests with mocked repositories, which prove the service asks for the right
  things — not that the transaction commits atomically. That needs a real
  database and is deferred with the rest of the integration-test scope.

---

## Technology Notes (Stage 10)

| Area           | Choice                                                          |
| -------------- | --------------------------------------------------------------- |
| Language       | Java 21                                                          |
| Backend        | Spring Boot 4.1.0 (Web MVC, Actuator, Bean Validation)           |
| Persistence    | Spring Data JPA / Hibernate, MySQL Connector/J, Flyway (control-api only) |
| Security       | Spring Security + OAuth2 resource server, HS256 JWT (control-api only) |
| Boilerplate    | Lombok, for getters/setters on entities                          |
| Build          | Maven, via the Maven Wrapper in each project                     |
| Frontend       | React 19, TypeScript, Vite 6                                     |
| Routing        | react-router-dom 6                                               |
| Frontend state | React Context and component state — no Redux                     |
| Styling        | one hand-written stylesheet — no CSS framework                   |
| Frontend HTTP  | the browser `fetch` API — no HTTP client library                 |
| Frontend tests | Vitest + React Testing Library, jsdom                            |
| Incidents      | worker-written rows; Control API reads only                      |
| Events         | Spring Kafka 4.1 (`spring-boot-starter-kafka`), worker only      |
| Event delivery | transactional outbox → scheduled publisher → Kafka               |
| Notifications  | Kafka consumer → inbox → scheduled sender → SMTP                 |
| Email          | Spring `JavaMailSender`, plain text, any SMTP server             |

The frontend deliberately carries no state-management, styling, charting or HTTP
library. At this size each would add a dependency and a set of conventions
without removing work: there is no cross-screen shared state beyond the signed-in
user, and every screen's data belongs to that screen.

The two backend projects are independent Maven projects rather than modules of a
shared parent, so each can be opened and built on its own in an IDE.
