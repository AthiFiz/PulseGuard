# PulseGuard Architecture

This document describes the architecture as it stands at **Stage 4 — Monitor
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

Future responsibilities **[NOT YET IMPLEMENTED]**:

- incident APIs
- dashboard and reporting queries

### Monitor Worker

The Monitor Worker is a Spring Boot application deliberately kept **separate and
independently deployable** from the Control API.

Implemented today:

- `GET /actuator/health`
- `GET /api/v1/system/info`

It contains no monitoring logic whatsoever — no scheduling, no HTTP checking, no
persistence. It has **no database dependencies at all**: no Spring Data JPA, no
MySQL driver, no Flyway, no entities. Adding them now would duplicate the schema
across two independent Maven projects for no benefit, so worker database access
is deferred to the stage that actually needs it.

Future responsibilities **[NOT YET IMPLEMENTED]**:

- finding monitors that are due for a check
- executing HTTP health checks and enforcing timeouts
- measuring response time
- persisting monitor check results
- updating monitor state and tracking consecutive failures
- detecting outages and recoveries

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

## Current Architecture (Stage 4)

```text
                     ┌──────────────────┐
                     │ React Frontend   │
                     │ localhost:5173   │
                     └────────┬─────────┘
                              │
                              │ HTTP + Bearer JWT
                              ▼
                     ┌──────────────────────────────┐
                     │        Control API           │
                     │        localhost:8080        │
                     │                              │
                     │  ├── Authentication / JWT    │
                     │  ├── Project Management      │
                     │  └── Monitor Configuration   │
                     └──────────────┬───────────────┘
                                    │
                                    │ JDBC
                                    ▼
                     ┌──────────────────────────────┐
                     │            MySQL             │
                     │          pulseguard          │
                     │  users / projects / members  │
                     └──────────────────────────────┘


                     ┌──────────────────────────────┐
                     │       Monitor Worker         │
                     │       localhost:8081         │
                     │  [no database, no security]  │
                     └──────────────────────────────┘
```

The frontend does not yet authenticate — it still only calls the public
`/api/v1/system/info`. The login UI arrives in the frontend stage.

---

## Monitors: configured, but not yet checked

Stage 4 added everything needed to *describe* a monitor and nothing that acts on
one. No HTTP request is made to a monitored URL, no `monitor_checks` row is
written, and no monitor ever becomes `UP` or `DOWN`.

### The handover point is `next_check_at`

Creating or resuming a monitor sets `monitors.next_check_at` to the current UTC
instant, which means "this monitor is eligible to be checked now". Pausing sets
it to `NULL`, which means "do not schedule this at all".

**Nothing reads that column yet.** It is written so the queue is already
populated the moment a worker exists:

```text
Configured Monitor
       │
       │  next_check_at  ← written by the Control API (Stage 4)
       ▼
Monitor Worker            [NOT YET IMPLEMENTED — Task 05]
       │  claims monitors whose next_check_at has passed
       ▼
  HTTP Check              [NOT YET IMPLEMENTED]
       │
       ▼
  MonitorCheck            [table exists, no writer]
       │
       ▼
  UP / DOWN, incidents    [NOT YET IMPLEMENTED]
```

### Why clients cannot set status

The API exposes `pause` and `resume` rather than a general "set status"
endpoint. `UNKNOWN` and `PAUSED` are statements of intent, which a user is
entitled to make; `UP` and `DOWN` are conclusions drawn from observation, which
only the monitoring engine may assert. Resume therefore returns a monitor to
`UNKNOWN`, never `UP`, and leaves an already-`UP`/`DOWN` monitor untouched so an
accidental call cannot erase a real observation.

Time comes from an injected `java.time.Clock` rather than `Instant.now()`, which
keeps scheduling assertions deterministic in tests and gives the worker the same
seam when it needs to reason about due times.

### Security consideration for Task 05: SSRF

Once the worker starts issuing requests to user-supplied URLs, PulseGuard
becomes a **server-side request forgery** vector: anyone who can create a
monitor can make the server issue HTTP requests on their behalf, from inside the
network, and learn something from the outcome.

Stage 4 validates only that the URL is a syntactically valid `http`/`https` URI
with a host. That is deliberate — the application makes no requests yet, and
local addresses are genuinely useful while developing and demonstrating the
project. **Task 05 must decide how to handle at least the following before the
first outbound request is made:**

- `localhost` and `127.0.0.0/8`
- private ranges: `10/8`, `172.16/12`, `192.168/16`
- link-local `169.254/16`, and especially cloud metadata endpoints such as
  `169.254.169.254`
- IPv6 equivalents, including `::1` and unique-local addresses
- **DNS rebinding** — a hostname that resolves to a public address at validation
  time and a private one at request time, which means the check must happen at
  connection time, not at save time
- **redirects** — a permitted public URL that redirects into private space
- response size and time limits, so a hostile endpoint cannot exhaust the worker

A reasonable design is an allow/deny policy that is permissive by default in
local development and restrictive in deployed environments, enforced when the
connection is opened rather than when the monitor is saved.

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
monitor_checks    the result of each individual check [no writer yet]
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

## Technology Notes (Stage 4)

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
