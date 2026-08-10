# PulseGuard

PulseGuard is an API Monitoring and Incident Management Platform.

It lets teams register HTTP/HTTPS APIs, monitor their availability and response
time on a schedule, and manage the incidents raised when an API fails and
recovers.

---

## Current Status

**Stage 4 — Monitor Management**

The Control API now has stateless JWT authentication, project management, and
**monitor configuration**: register, log in, create a project, and configure the
API endpoints you want watched.

> **PulseGuard does not check anything yet.** Task 04 stores and validates
> monitor configuration only. No HTTP request is ever made to a monitored URL,
> no check history is recorded, and no monitor will ever become `UP` or `DOWN`.
> That arrives with the Monitor Worker in the next stage.

Still **not implemented**: the monitoring engine, scheduling, incidents, Kafka,
notifications, and the entire frontend beyond the Stage 1 connectivity page.

Only the Control API talks to the database. The Monitor Worker deliberately has
no persistence or security dependencies until the stage that needs them.

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


                     ┌──────────────────┐
                     │ Monitor Worker   │
                     │ localhost:8081   │
                     │ [no database yet]│
                     └──────────────────┘
```

The Monitor Worker is currently completely independent. It does not talk to the
Control API or the database, it holds no monitoring logic, and nothing calls it
except its own health endpoint. It exists now so that background monitoring work
has a home in later stages.

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

```bash
cd backend/monitor-worker
./mvnw spring-boot:run
```

Starts on <http://localhost:8081>.

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
| `UP` / `DOWN` | Observed health | **the future monitoring engine — never a client** |

**Pause** sets `PAUSED`, clears `nextCheckAt` so the monitor leaves the
schedule, and resets `consecutiveFailures`. It keeps `lastCheckedAt`, which
records something that really happened. Pausing twice is harmless.

**Resume** returns a paused monitor to `UNKNOWN` and schedules it immediately.
It deliberately does *not* set `UP` — no check has run. Resuming a monitor that
is already `UP`, `DOWN`, or `UNKNOWN` changes nothing, so an accidental call
cannot discard a real observed state.

### Roles

| Role            | Scope    | Can do                                              |
| --------------- | -------- | --------------------------------------------------- |
| `USER`          | platform | the default; access comes only from membership      |
| `ADMIN`         | platform | read and manage every project, without membership   |
| `PROJECT_ADMIN` | project  | update/delete the project, manage members and monitors |
| `VIEWER`        | project  | read the project, its members and its monitors      |

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
Monitoring Worker
Dashboard
Incident Management
Kafka
Notifications
Docker
SonarQube
Jenkins
AWS
Kubernetes
Observability
```

The next stage is **Task 05 — Monitoring Worker / HTTP Health Check Engine**.
