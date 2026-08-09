# PulseGuard Architecture

This document describes the architecture as it stands at **Stage 1 — Project
Foundation**, and the shape it is intended to grow into.

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

Future responsibilities **[NOT YET IMPLEMENTED]**:

- authentication and authorization
- users, projects, and project membership
- monitor configuration
- incident APIs
- dashboard and reporting queries

### Monitor Worker

The Monitor Worker is a Spring Boot application deliberately kept **separate and
independently deployable** from the Control API.

Implemented today:

- `GET /actuator/health`
- `GET /api/v1/system/info`

It contains no monitoring logic whatsoever — no scheduling, no HTTP checking, no
persistence.

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

## Current Architecture (Stage 1)

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
                     └──────────────────┘


                     ┌──────────────────┐
                     │ Monitor Worker   │
                     │ localhost:8081   │
                     └──────────────────┘
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

**MySQL** is the selected relational database for PulseGuard.

Local development will use a MySQL container, integration tests will use MySQL
Testcontainers, and production will eventually use **Amazon RDS for MySQL**.

No database of any kind has been added yet — that is Task 02.

---

## Technology Notes (Stage 1)

| Area          | Choice                                                   |
| ------------- | -------------------------------------------------------- |
| Language      | Java 21                                                   |
| Backend       | Spring Boot 4.1.0 (Web MVC, Actuator, Bean Validation)    |
| Build         | Maven, via the Maven Wrapper in each project              |
| Frontend      | React 19, TypeScript, Vite 6                              |
| Frontend HTTP | the browser `fetch` API — no HTTP client library          |

The two backend projects are independent Maven projects rather than modules of a
shared parent, so each can be opened and built on its own in an IDE.
