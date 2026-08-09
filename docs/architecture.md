# PulseGuard Architecture

This document describes the architecture as it stands at **Stage 2 — MySQL
Database Foundation**, and the shape it is intended to grow into.

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

- `GET /actuator/health`
- `GET /api/v1/system/info`
- configurable CORS for local frontend development
- **ownership of the PulseGuard relational schema**: Flyway migrations, JPA
  entities, and Spring Data repositories

Future responsibilities **[NOT YET IMPLEMENTED]**:

- authentication and authorization
- users, projects, and project membership
- monitor configuration
- incident APIs
- dashboard and reporting queries

Note that the entities and repositories exist but no code reads or writes them
yet — no service layer and no business REST endpoints have been built.

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

## Current Architecture (Stage 2)

```text
                     ┌──────────────────┐
                     │ React Frontend   │
                     │ localhost:5173   │
                     └────────┬─────────┘
                              │
                              │ HTTP
                              ▼
                     ┌──────────────────┐
                     │   Control API    │
                     │ localhost:8080   │
                     └────────┬─────────┘
                              │
                              │ JDBC
                              ▼
                     ┌──────────────────┐
                     │      MySQL       │
                     │    pulseguard    │
                     └──────────────────┘


                     ┌──────────────────────────────┐
                     │       Monitor Worker         │
                     │       localhost:8081         │
                     │ [database access deferred]   │
                     └──────────────────────────────┘
```

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
monitors          monitored endpoints and their configuration and state
monitor_checks    the result of each individual check
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

## Technology Notes (Stage 2)

| Area          | Choice                                                          |
| ------------- | --------------------------------------------------------------- |
| Language      | Java 21                                                          |
| Backend       | Spring Boot 4.1.0 (Web MVC, Actuator, Bean Validation)           |
| Persistence   | Spring Data JPA / Hibernate, MySQL Connector/J, Flyway (control-api only) |
| Boilerplate   | Lombok, for getters/setters on entities                          |
| Build         | Maven, via the Maven Wrapper in each project                     |
| Frontend      | React 19, TypeScript, Vite 6                                     |
| Frontend HTTP | the browser `fetch` API — no HTTP client library                 |

The two backend projects are independent Maven projects rather than modules of a
shared parent, so each can be opened and built on its own in an IDE.
