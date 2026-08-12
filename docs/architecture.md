# PulseGuard Architecture

This document describes the architecture as it stands at **Stage 6 — Monitoring
History, Statistics and Dashboard APIs**, and the shape it is intended to grow
into.

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

None of that behaviour exists yet. Stage 1 establishes only the runnable
skeleton.

---

## Components

### React Frontend

The React application provides the entire user-facing interface.

Implemented today: an application shell that calls the Control API once on load
and reports whether the backend is reachable.

Future responsibilities **[NOT YET IMPLEMENTED]**:

- login
- project and monitor management screens
- dashboard
- response-time charts and monitoring history
- incident list and incident detail views

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

Future responsibilities **[NOT YET IMPLEMENTED]**:

- incident APIs

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

It connects to the same MySQL database as the Control API but maps only
`monitors` and `monitor_checks`, with `project_id` and `monitor_id` as plain
`Long` columns rather than JPA associations — the worker performs no project
authorization, so the whole project-management model would be dead weight.

**It runs no migrations.** Flyway lives entirely in the Control API; the worker
uses `ddl-auto=validate` and would fail to start if the schema did not match
what it expects.

Future responsibilities **[NOT YET IMPLEMENTED]**:

- opening and resolving incidents
- publishing events to Kafka
- coordinating multiple worker replicas

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

## Current Architecture (Stage 6)

```text
                     ┌──────────────────┐
                     │ React Frontend   │
                     │ localhost:5173   │
                     └────────┬─────────┘
                              │ HTTP + Bearer JWT
                              ▼
        ┌──────────────────────────────────────────┐
        │            Control API  :8080            │
        │              CONFIGURATION PLANE         │
        │  Authentication · Projects · Monitors    │
        │  Monitoring history · statistics · dashboard │
        │  Owns the Flyway migrations              │
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

The frontend still does not authenticate — it only calls the public
`/api/v1/system/info`. The login UI arrives in the frontend stage.

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
        ▼                          update monitors state + schedule
```

**No database transaction is ever held open across the HTTP call.** The due
query finishes and detaches its rows, the request happens with nothing open, and
the result is written in its own short transaction afterwards. A monitored
endpoint that takes 30 seconds to answer therefore cannot pin a database
connection for 30 seconds.

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

### Not implemented at this stage

No incidents, no Kafka events, no notifications, and **no distributed locking**.
Exactly one worker instance is assumed — two workers against the same database
would both see the same due monitors and check everything twice. Coordination
(`SELECT … FOR UPDATE SKIP LOCKED`) belongs to the Kubernetes scaling stage.

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
  being canonical.

These belong to the Testing and Hardening stage.

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
```

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

## Technology Notes (Stage 6)

| Area          | Choice                                                          |
| ------------- | --------------------------------------------------------------- |
| Language      | Java 21                                                          |
| Backend       | Spring Boot 4.1.0 (Web MVC, Actuator, Bean Validation)           |
| Persistence   | Spring Data JPA / Hibernate, MySQL Connector/J, Flyway (control-api only) |
| Security      | Spring Security + OAuth2 resource server, HS256 JWT (control-api only) |
| Boilerplate   | Lombok, for getters/setters on entities                          |
| Build         | Maven, via the Maven Wrapper in each project                     |
| Frontend      | React 19, TypeScript, Vite 6                                     |
| Frontend HTTP | the browser `fetch` API — no HTTP client library                 |

The two backend projects are independent Maven projects rather than modules of a
shared parent, so each can be opened and built on its own in an IDE.
