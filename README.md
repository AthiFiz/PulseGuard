# PulseGuard

PulseGuard is an API Monitoring and Incident Management Platform.

It lets teams register HTTP/HTTPS APIs, monitor their availability and response
time on a schedule, and manage the incidents raised when an API fails and
recovers.

---

## Current Status

**Stage 8 — Incident Management**

PulseGuard now keeps a record of **outages**, not just checks. When a monitor
reaches its failure threshold an incident opens; when the endpoint answers again
it resolves. Repeated failures during one outage stay one incident.

Everything is usable from a browser: register and sign in, create projects,
invite members, configure monitors, watch status and history, and read the
project's incident history.

Still **not implemented**: Kafka and notifications. An incident opening is a row
in the database and a red badge in the UI — nobody is told.

All three applications share one MySQL database: the Control API owns the schema
and the configuration, the worker executes the checks, and the frontend talks
only to the Control API.

---

## Current Architecture

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
                     │ localhost:3306   │
                     │    pulseguard    │
                     └──────────────────┘


                              ▲
                              │ JDBC
                     ┌────────┴─────────┐
                     │ Monitor Worker   │
                     │ localhost:8081   │
                     └────────┬─────────┘
                              │ HTTP GET
                              ▼
                     ┌──────────────────┐
                     │ Monitored APIs   │
                     └──────────────────┘
```

The two backends never talk to each other — they meet in the database. The
Control API is the configuration plane: it owns the schema and decides what
should be monitored. The Monitor Worker is the execution plane: it reads that
configuration, performs the checks, and writes the results back.

---

## Prerequisites

```text
Java 21
Node.js 18+
npm
MySQL 8 (running locally)
```

Maven is **not** required — both backend projects ship the Maven Wrapper
(`./mvnw`), which downloads the correct Maven version automatically.

---

## Database Setup

Local development requires a **locally installed MySQL 8 server**. PulseGuard
does not create databases for you — create it once, and Flyway takes over from
there:

```sql
CREATE DATABASE pulseguard
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
```

Flyway creates and owns every table, index, and constraint inside that database.
It runs automatically each time the Control API starts, and Hibernate then
validates that the entity mappings match the migrated schema. Hibernate never
generates or alters the schema itself (`ddl-auto=validate`).

Migrations live in `backend/control-api/src/main/resources/db/migration` and are
applied in version order:

```text
V1__create_users.sql
V2__create_projects.sql
V3__create_project_members.sql
V4__create_monitors.sql
V5__create_monitor_checks.sql
V6__create_incidents.sql
```

Never edit a migration that has already been applied — add a new one.

---

## Repository Structure

```text
pulseguard/
├── backend/
│   ├── control-api/        Spring Boot application (independent Maven project)
│   │   └── src/main/
│   │       ├── java/.../domain/        JPA entities
│   │       ├── java/.../domain/enums/  persisted enums
│   │       ├── java/.../repository/    Spring Data repositories
│   │       └── resources/db/migration/ Flyway migrations
│   └── monitor-worker/     Spring Boot application (independent Maven project)
├── frontend/               React + TypeScript + Vite application
├── docs/
│   └── architecture.md
├── .editorconfig
├── .gitignore
└── README.md
```

`backend/` is a plain folder, not a Maven aggregator. `control-api` and
`monitor-worker` are two fully independent Maven projects and can each be opened
on their own in IntelliJ IDEA.

---

## Running the Control API

The Control API needs database credentials and a JWT signing secret. Supply them
as environment variables — never commit them:

```bash
export DB_URL="jdbc:mysql://localhost:3306/pulseguard?connectionTimeZone=UTC&preserveInstants=true"
export DB_USERNAME="YOUR_MYSQL_USERNAME"
export DB_PASSWORD="YOUR_MYSQL_PASSWORD"

# Generate a local signing secret once and keep it out of version control:
#   openssl rand -base64 32
export JWT_SECRET="YOUR_BASE64_SECRET"

cd backend/control-api
./mvnw spring-boot:run
```

`JWT_SECRET` has **no default**. It must be Base64 and decode to at least 32
bytes (256 bits) for HS256; anything shorter or malformed fails startup with an
explicit message rather than silently weakening token signing.

Starts on <http://localhost:8080>. On startup Flyway applies any pending
migrations and Hibernate validates the schema, so a mapping that disagrees with
the database stops the application immediately with a clear error.

`DB_URL` defaults to the local `pulseguard` database if unset, but there is no
default username or password.

---

## Running the Monitor Worker

The worker now needs the **same database** as the Control API. It will not start
without it — that is deliberate, since a worker that cannot reach MySQL cannot
monitor anything.

```bash
export DB_URL="jdbc:mysql://localhost:3306/pulseguard?connectionTimeZone=UTC&preserveInstants=true"
export DB_USERNAME="YOUR_MYSQL_USERNAME"
export DB_PASSWORD="YOUR_MYSQL_PASSWORD"

cd backend/monitor-worker
./mvnw spring-boot:run
```

Starts on <http://localhost:8081>.

### What it does

Every few seconds it looks for monitors whose `nextCheckAt` has passed, and for
each one: validates the destination, sends a `GET`, measures the response time,
compares the status against the monitor's `expectedStatusCode`, writes a row to
`monitor_checks`, and updates the monitor's state.

The polling interval is **not** a monitor's check interval. The worker wakes on
its own cadence and checks only what is due, so a 5-second poll and a 60-second
monitor produce one check per minute, not twelve.

### Worker configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local `pulseguard` | Same database as the Control API |
| `MONITOR_POLL_INTERVAL` | `PT5S` | How often to look for due monitors |
| `MONITOR_BATCH_SIZE` | `50` | Most monitors processed in one cycle |
| `MONITOR_ALLOW_PRIVATE_ADDRESSES` | `false` | Allow loopback/private destinations |

### Monitoring something on your own machine

By default the worker refuses destinations that resolve to loopback or private
addresses, because it sends requests to URLs any user can supply. To point a
monitor at something local — the Control API's own health endpoint, say — start
the worker with:

```bash
MONITOR_ALLOW_PRIVATE_ADDRESSES=true ./mvnw spring-boot:run
```

Cloud metadata endpoints such as `169.254.169.254` stay blocked either way.

### One worker only

Running two worker instances against the same database will double-check every
monitor. There is no locking yet — coordination arrives with the Kubernetes
scaling stage.

### No incidents

A monitor reaching `DOWN` updates its status and writes check history. It does
**not** open an incident, publish an event, or notify anyone. That is a later
stage.

---

## Running the Frontend

```bash
cd frontend
cp .env.example .env      # only needed if you want to override defaults
npm install
npm run dev
```

Starts on <http://localhost:5173>.

The frontend reads the Control API location from `VITE_API_BASE_URL`. If no
`.env` file is present it falls back to `http://localhost:8080`. Do not commit
your own `.env` — only `.env.example` is tracked.

The origin above must also be allowed by the Control API's CORS configuration
(`FRONTEND_ORIGIN`, which already defaults to it). Changing the Vite port means
changing both.

### Running all three together

Each process runs in its own terminal, in this order:

```bash
# 1. Control API — owns the schema, so it migrates the database first
cd backend/control-api && ./mvnw spring-boot:run

# 2. Monitor Worker — MONITOR_ALLOW_PRIVATE_ADDRESSES is only needed
#    if you want to monitor something on your own machine
cd backend/monitor-worker && MONITOR_ALLOW_PRIVATE_ADDRESSES=true ./mvnw spring-boot:run

# 3. Frontend
cd frontend && npm run dev
```

The frontend works without the worker — monitors simply stay `UNKNOWN`, because
nothing is checking them.

---

## The Frontend

A single-page React application. Every screen is a view over the Control API;
the frontend holds no rules of its own and computes nothing the API already
reports.

```text
/login  /register          public; a signed-in visitor is sent to /projects
/projects                  the projects you belong to, and project creation
/projects/:id/dashboard    monitor counts, 24-hour check figures, recent failures
/projects/:id/monitors     every monitor with its current status
/projects/:id/incidents    outage history, filterable by status
/projects/:id/members      members and their roles
/projects/:id/settings     rename or delete the project        (PROJECT_ADMIN)
/projects/:id/monitors/new create a monitor                    (PROJECT_ADMIN)
/monitors/:id              status, configuration, statistics, check history
/monitors/:id/edit         reconfigure a monitor               (PROJECT_ADMIN)
/incidents/:id             one outage, read-only for every member
```

### Sessions

Signing in stores the access token in `sessionStorage`, so it lasts for the tab
and disappears when the tab closes. On every load the application calls
`GET /api/v1/auth/me` before rendering anything behind the guard: a stored token
is a claim, and the API is what verifies it. That is why a browser refresh on a
deep URL restores the session instead of bouncing to the login page.

Any authenticated request rejected with `401` clears the token and returns the
visitor to `/login`. A failed *login* is a `401` too and is deliberately excluded
— it shows "Invalid email or password" on the form instead.

Registering does not sign you in; it returns you to the login page with a
confirmation. There is no refresh token, so an expired session means signing in
again.

### Roles in the UI

A `VIEWER` sees a **Read only** badge, and the actions they cannot perform are
not rendered: no *New monitor*, no *Edit*, *Pause* or *Delete*, no member
management, and no Settings tab. Typing a management URL directly reaches a
refusal rather than a form.

This is presentation, not enforcement. Every one of those calls is authorised
again by the Control API, which is the only thing standing between a request and
the database.

### Testing the frontend

```bash
cd frontend
npm test          # watch mode
npx vitest run    # single run
npm run typecheck
```

---

## Running Backend Tests

Each project is built separately:

```bash
cd backend/control-api
./mvnw clean verify
```

```bash
cd backend/monitor-worker
./mvnw clean verify
```

**No database is required to build or test.** The test suite is deliberately
limited to fast tests that need no external infrastructure — there is no
database integration-test scope in this project.

The consequence is that the schema is not covered by automated tests. Flyway
migrations and Hibernate schema validation are exercised by **starting the
Control API** against MySQL: if a migration is broken or an entity mapping
disagrees with the schema, startup fails.

---

## Building the Frontend

```bash
cd frontend
npm run build
```

---

## Configuration

Both backend applications read these optional environment variables:

| Variable          | Applies to  | Default                             | Purpose                    |
| ----------------- | ----------- | ----------------------------------- | -------------------------- |
| `SERVER_PORT`     | both        | `8080` / `8081`                     | HTTP port                  |
| `FRONTEND_ORIGIN` | Control API | `http://localhost:5173`             | Origin allowed by CORS     |
| `DB_URL`          | Control API | local `pulseguard` database         | JDBC URL                   |
| `DB_USERNAME`     | Control API | *(none — must be set)*              | MySQL user                 |
| `DB_PASSWORD`     | Control API | *(none — must be set)*              | MySQL password             |
| `JWT_SECRET`      | Control API | *(none — must be set)*              | Base64 HS256 signing key   |
| `JWT_EXPIRATION`  | Control API | `PT1H`                              | Access token lifetime      |
| `JWT_ISSUER`      | Control API | `pulseguard-control-api`            | Expected token issuer      |

The frontend reads:

| Variable            | Default                 | Purpose               |
| ------------------- | ----------------------- | --------------------- |
| `VITE_API_BASE_URL` | `http://localhost:8080` | Control API base URL  |

Database credentials are the only secrets so far, and they are supplied through
the environment. Nothing sensitive is committed.

---

## API

Base path `/api/v1`. Only these four endpoints are public — everything else
requires a bearer token:

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/system/info
GET  /actuator/health
```

### Authentication

```text
POST /api/v1/auth/register    create an account (always a normal USER)
POST /api/v1/auth/login       exchange credentials for an access token
GET  /api/v1/auth/me          the caller's own account
```

Send the token on every other request:

```http
Authorization: Bearer <accessToken>
```

Example:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"SecurePassword123!","displayName":"You"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"SecurePassword123!"}' | jq -r .accessToken)

curl http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
```

Login failures are deliberately indistinguishable: an unknown email, a wrong
password, and a disabled account all return the same `401 INVALID_CREDENTIALS`.

There is no refresh token and no logout endpoint. When the access token
expires, log in again; "logging out" means the client discarding its token.

### Projects

```text
POST   /api/v1/projects              create (creator becomes PROJECT_ADMIN)
GET    /api/v1/projects              list projects you belong to
GET    /api/v1/projects/{id}         read
PUT    /api/v1/projects/{id}         update      (PROJECT_ADMIN)
DELETE /api/v1/projects/{id}         delete      (PROJECT_ADMIN)
```

### Project members

```text
GET    /api/v1/projects/{id}/members             list        (any member)
POST   /api/v1/projects/{id}/members             add         (PROJECT_ADMIN)
PUT    /api/v1/projects/{id}/members/{memberId}  change role (PROJECT_ADMIN)
DELETE /api/v1/projects/{id}/members/{memberId}  remove      (PROJECT_ADMIN)
```

Members are added by the email of an **already registered** user; an unknown
address returns `404 USER_NOT_FOUND` rather than creating or inviting anyone.

### Monitors

A monitor is one HTTP endpoint you want watched, together with how it should be
checked. Monitors live inside a project, and permission to touch them comes
entirely from project membership.

```text
POST   /api/v1/projects/{projectId}/monitors   create      (PROJECT_ADMIN)
GET    /api/v1/projects/{projectId}/monitors   list        (any member)

GET    /api/v1/monitors/{monitorId}            read        (any member)
PUT    /api/v1/monitors/{monitorId}            reconfigure (PROJECT_ADMIN)
DELETE /api/v1/monitors/{monitorId}            delete      (PROJECT_ADMIN)

POST   /api/v1/monitors/{monitorId}/pause      pause       (PROJECT_ADMIN)
POST   /api/v1/monitors/{monitorId}/resume     resume      (PROJECT_ADMIN)
```

Create one with:

```bash
curl -X POST http://localhost:8080/api/v1/projects/1/monitors \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "name": "Payment API",
        "description": "Production payment service health endpoint",
        "url": "https://api.example.com/actuator/health",
        "httpMethod": "GET",
        "expectedStatusCode": 200,
        "intervalSeconds": 60,
        "timeoutSeconds": 5,
        "failureThreshold": 3
      }'
```

#### Configuration fields

| Field | Meaning | Rules |
| --- | --- | --- |
| `name` | Label shown in the UI | required, 2–150 chars, trimmed |
| `description` | Free text | optional, ≤ 1000 chars; blank becomes `null` |
| `url` | Endpoint to check | required, ≤ 2048 chars, **`http`/`https` only** |
| `httpMethod` | Request method | **`GET` only** in this MVP |
| `expectedStatusCode` | The status that counts as healthy | 100–599 |
| `intervalSeconds` | How often to check | 30–86400 |
| `timeoutSeconds` | How long to wait for a reply | 1–30, **must be less than the interval** |
| `failureThreshold` | Consecutive failures before an incident | 1–10 |

Operational fields — `currentStatus`, `consecutiveFailures`, `lastCheckedAt`,
`nextCheckAt` — are returned but never accepted. There is no request field for
any of them, so a client cannot declare a monitor healthy or reschedule it.

A monitor also cannot be moved between projects. Access derives from the
project, so reassigning one would silently change who can see it.

#### Statuses

| Status | Meaning | Set by |
| --- | --- | --- |
| `UNKNOWN` | Never successfully checked | creation and resume |
| `PAUSED` | Deliberately not scheduled | pause |
| `UP` / `DOWN` | Observed health | **the Monitor Worker — never a client** |

```text
UNKNOWN ──── successful check ────► UP
                                     │
                          consecutive failures
                            reach the threshold
                                     ▼
                                   DOWN
                                     │
                            successful check
                                     ▼
                                    UP

PAUSED ────── resume ──────► UNKNOWN
```

A failure below the threshold **keeps the current status**, so one blip does not
flip a healthy monitor to `DOWN`. A success resets the failure count from any
state. Reaching `DOWN` produces no incident and no notification.

**Pause** sets `PAUSED`, clears `nextCheckAt` so the monitor leaves the
schedule, and resets `consecutiveFailures`. It keeps `lastCheckedAt`, which
records something that really happened. Pausing twice is harmless.

**Resume** returns a paused monitor to `UNKNOWN` and schedules it immediately.
It deliberately does *not* set `UP` — no check has run. Resuming a monitor that
is already `UP`, `DOWN`, or `UNKNOWN` changes nothing, so an accidental call
cannot discard a real observed state.

### Monitoring history, statistics and dashboard

Read-only views over what the worker has recorded. **Any project member,
including a `VIEWER`, may read all of these** — a viewer cannot change a monitor,
but can see everything it has done.

```text
GET /api/v1/monitors/{monitorId}/checks       paginated check history
GET /api/v1/monitors/{monitorId}/statistics   uptime and response times
GET /api/v1/projects/{projectId}/dashboard    project snapshot
```

#### Check history

```bash
curl "http://localhost:8080/api/v1/monitors/25/checks?page=0&size=50" \
  -H "Authorization: Bearer $TOKEN"
```

Always paginated — `monitor_checks` grows by one row per check forever. Sorted
**newest first**.

| Parameter | Default | Notes |
| --- | --- | --- |
| `page` | `0` | Must not be negative |
| `size` | `50` | Maximum `100`; a larger value is **rejected**, not clamped |
| `from` | – | Inclusive ISO-8601 instant, e.g. `2026-08-01T00:00:00Z` |
| `to` | – | Inclusive |
| `outcome` | – | `SUCCESS` or `FAILURE`; omitted returns both |

The response is a stable envelope rather than Spring's internal `Page`:

```json
{ "content": [ … ], "page": 0, "size": 50, "totalElements": 201,
  "totalPages": 5, "first": true, "last": false }
```

A monitor with no checks returns `200` with an empty `content`, not a `404`.

#### Statistics

```bash
curl "http://localhost:8080/api/v1/monitors/25/statistics" \
  -H "Authorization: Bearer $TOKEN"
```

With no range this covers **all recorded history**. `from` and `to` narrow it.

```json
{
  "monitorId": 25, "from": null, "to": null,
  "totalChecks": 1440, "successfulChecks": 1435, "failedChecks": 5,
  "uptimePercentage": 99.65,
  "averageResponseTimeMs": 121.42,
  "minimumResponseTimeMs": 82, "maximumResponseTimeMs": 645,
  "lastCheckedAt": "2026-08-12T08:30:00Z", "currentStatus": "UP"
}
```

Two behaviours worth knowing:

- **`null` means "no data", not zero.** A monitor with no checks reports
  `uptimePercentage: null` — it has not been down, its availability is simply
  unknown. Same for the response-time figures when no check recorded a duration
  (a run of DNS failures, say). Returning `0` would claim an outage that never
  happened, or an implausibly fast service.
- **Response times ignore checks that never got a response.** A DNS failure or a
  blocked destination has no duration and is excluded from the average rather
  than counted as `0ms`.

`lastCheckedAt` is the newest check *within the requested range*, which is not
necessarily the monitor's own last check.

#### Project dashboard

```bash
curl "http://localhost:8080/api/v1/projects/10/dashboard" \
  -H "Authorization: Bearer $TOKEN"
```

**Defaults to the last 24 hours** — deliberately unlike statistics, where no
range means all history. A dashboard answers "how are things now", so an outage
last March should not colour today's number. Pass `from` and `to` to override.

```json
{
  "projectId": 10,
  "generatedAt": "2026-08-12T08:30:00Z",
  "window": { "from": "2026-08-11T08:30:00Z", "to": "2026-08-12T08:30:00Z" },
  "monitors": { "total": 10, "up": 7, "down": 1, "unknown": 1, "paused": 1 },
  "openIncidents": 1,
  "checks": { "total": 1430, "successful": 1400, "failed": 30,
              "uptimePercentage": 97.90, "averageResponseTimeMs": 132.50 },
  "recentFailures": [ … ]
}
```

`monitors` and `openIncidents` are **current state and ignore the window** —
changing the range does not change those counts. `checks` and `recentFailures`
describe the window.

`openIncidents` is one aggregate query, not one per monitor, so the dashboard
costs four queries whether the project has three monitors or five hundred. An
outage that began last week is still open today, which is exactly why narrowing
the window must not hide it.

Project uptime is aggregated **across individual checks**, never by averaging
each monitor's percentage. With a monitor on 1000 successful checks and another
on a single failure, the honest figure is 99.90%; averaging the two percentages
would say 50%.

`recentFailures` holds the 10 most recent failed checks in the window, newest
first, taken from the checks themselves — a monitor that is healthy right now may
still have failed twenty minutes ago.

> **Uptime here is calculated from successful monitoring checks, not from
> measured incident duration.** It is a descriptive figure, not an SLA
> calculation. Incidents now exist and record real outage durations, but nothing
> uses them for availability yet — that is a deliberate later step.

### Incidents

An incident is **one continuous outage**, not one failed check. This is the
whole idea, so it is worth stating plainly:

```text
failure threshold reached
        ↓
   Monitor DOWN
        ↓
  Incident OPEN
```

```text
successful check
        ↓
    Monitor UP
        ↓
Incident RESOLVED
```

**Repeated failures during one outage do not create repeated incidents.** With
`failureThreshold = 3`:

```text
Failure 1                      monitor stays UP        0 incidents
Failure 2                      monitor stays UP        0 incidents
Failure 3   → DOWN             incident #1 OPEN        1 incident
Failure 4                      incident #1 OPEN        1 incident
Failure 5                      incident #1 OPEN        1 incident
Success     → UP               incident #1 RESOLVED    1 incident

… later …

Failures 1-3 → DOWN            incident #2 OPEN        2 incidents
```

Incident #1 stays `RESOLVED` forever. Rows are never reused, so the history is
a real record of distinct outages.

#### Monitor status is not an incident

Two different questions, deliberately kept apart:

| | Answers | Lives in | Changes |
| --- | --- | --- | --- |
| `MonitorStatus` | "is this endpoint healthy **right now**?" | `monitors.current_status` | overwritten on every check |
| `Incident` | "what happened, and for how long?" | a row in `incidents` | never rewritten once resolved |

A monitor showing `UP` today can sit in a project with dozens of `RESOLVED`
incidents behind it. Neither contradicts the other.

#### Statuses

```text
OPEN       the outage is still going: no successful check since it began
RESOLVED   a successful check ended it
```

There is no `ACKNOWLEDGED`, no severity, no assignee and no comments. Those need
a person acting on an incident, and PulseGuard offers nobody that yet.

#### Pause and resume do not resolve anything

Pausing a monitor stops PulseGuard checking it. That is not evidence the
monitored service recovered, so:

```text
DOWN + open incident
   ↓ pause          → PAUSED,  incident still OPEN
   ↓ resume         → UNKNOWN, incident still OPEN
   ↓ first success  → UP,      incident RESOLVED
```

Only a genuine successful check closes an incident. Note the third line: the
monitor is `UNKNOWN` rather than `DOWN` when the success arrives, which is why
resolution is tied to the success itself and not to the previous status.

#### Endpoints

```text
GET /api/v1/projects/{projectId}/incidents   paginated history (any member)
GET /api/v1/incidents/{incidentId}           one incident      (any member)
```

There is no `POST`, `PUT` or `DELETE`. Incidents are written by the Monitor
Worker from observed checks; nothing a user types can take a service down or
bring it back.

```bash
curl "http://localhost:8080/api/v1/projects/10/incidents?status=OPEN" \
  -H "Authorization: Bearer $TOKEN"
```

| Parameter | Default | Notes |
| --- | --- | --- |
| `page` | `0` | Must not be negative |
| `size` | `20` | Maximum `100`; a larger value is **rejected**, not clamped |
| `status` | – | `OPEN` or `RESOLVED`; omitted returns both |
| `from` | – | Inclusive ISO-8601 instant, filtering on `openedAt` |
| `to` | – | Inclusive |

Sorted **newest first** by `openedAt`, in the same `PageResponse` envelope as
check history.

```json
{
  "id": 41,
  "projectId": 10,
  "monitorId": 25,
  "monitorName": "Payment API",
  "status": "RESOLVED",
  "openedAt": "2026-08-12T10:10:00Z",
  "resolvedAt": "2026-08-12T10:18:00Z",
  "openingCheckId": 1501,
  "resolutionCheckId": 1517
}
```

An `OPEN` incident reports `"resolvedAt": null` and
`"resolutionCheckId": null`. Nothing invents an end time for an outage that has
not ended.

Both timestamps are **check timestamps, not row-insert times**, so the duration
between them describes the monitored service rather than the worker. There is no
`duration` field: it is exactly `resolvedAt - openedAt`, and storing a derived
value is one more thing that can disagree with the facts it came from.

`openingCheckId` and `resolutionCheckId` point at the rows in `monitor_checks`
that caused each transition — the raw evidence behind the two timestamps.

Access is inherited from the monitor's project. A non-member asking for an
incident gets `404 INCIDENT_NOT_FOUND`, identical to a genuinely missing one, so
ids reveal nothing about other people's outages.

### Roles

| Role            | Scope    | Can do                                              |
| --------------- | -------- | --------------------------------------------------- |
| `USER`          | platform | the default; access comes only from membership      |
| `ADMIN`         | platform | read and manage every project, without membership   |
| `PROJECT_ADMIN` | project  | update/delete the project, manage members and monitors |
| `VIEWER`        | project  | read the project, its members, monitors and all monitoring data |

A project must always keep at least one `PROJECT_ADMIN` — the last one can be
neither demoted nor removed (`409 PROJECT_REQUIRES_ADMIN`). Non-members receive
`404` rather than `403`, so project ids cannot be probed for existence.

### Errors

Every failure, including security rejections, uses one JSON shape:

```json
{
  "timestamp": "2026-08-09T10:00:00Z",
  "status": 409,
  "code": "EMAIL_ALREADY_REGISTERED",
  "message": "An account with this email already exists",
  "path": "/api/v1/auth/register",
  "errors": []
}
```

---

## Verification URLs

```text
Frontend
http://localhost:5173

Control API Health
http://localhost:8080/actuator/health

Control API System Info
http://localhost:8080/api/v1/system/info

Monitor Worker Health
http://localhost:8081/actuator/health

Monitor Worker System Info
http://localhost:8081/api/v1/system/info
```

---

## Roadmap

Later stages will introduce, roughly in this order:

```text
Kafka
Notifications
Docker
SonarQube
Jenkins
AWS
Kubernetes
Observability
```

The next stage is **Task 09 — Kafka Event Streaming**.
