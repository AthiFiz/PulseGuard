# PulseGuard Architecture

This document describes the architecture as it stands at **Stage 8 — Incident
Management**, and the shape it is intended to grow into.

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

It **never writes an incident.** Opening and resolving one is a statement about
what a check observed, so only the worker may make it.

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

It connects to the same MySQL database as the Control API but maps only
`monitors`, `monitor_checks` and `incidents`, with `project_id`, `monitor_id`
and the check references as plain `Long` columns rather than JPA associations —
the worker performs no project authorization, so the whole project-management
model would be dead weight.

**It runs no migrations.** Flyway lives entirely in the Control API; the worker
uses `ddl-auto=validate` and would fail to start if the schema did not match
what it expects.

Future responsibilities **[NOT YET IMPLEMENTED]**:

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
        ▼                          open or resolve the incident
```

**No database transaction is ever held open across the HTTP call.** The due
query finishes and detaches its rows, the request happens with nothing open, and
the result is written in its own short transaction afterwards. A monitored
endpoint that takes 30 seconds to answer therefore cannot pin a database
connection for 30 seconds.

The check row, the monitor's new state and the incident change are written in
**that same short transaction**, so a monitor can never be committed as `DOWN`
without the incident that explains it.

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

### Not implemented at this stage

**No Kafka events and no notifications.** An incident opening is recorded in the
database and shown in the UI; nobody is told. Event publishing arrives in the
Kafka stage, and email in the notification stage.

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

## Technology Notes (Stage 8)

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

The frontend deliberately carries no state-management, styling, charting or HTTP
library. At this size each would add a dependency and a set of conventions
without removing work: there is no cross-screen shared state beyond the signed-in
user, and every screen's data belongs to that screen.

The two backend projects are independent Maven projects rather than modules of a
shared parent, so each can be opened and built on its own in an IDE.
