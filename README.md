# PulseGuard

PulseGuard is an API Monitoring and Incident Management Platform.

It lets teams register HTTP/HTTPS APIs, monitor their availability and response
time on a schedule, and manage the incidents raised when an API fails and
recovers.

---

## Current Status

**Stage 1 — Project Foundation**

Only the project skeleton exists. The monitoring engine, incident management,
and all other business functionality are **not implemented yet**.

There is no database, no authentication, no scheduling, and no messaging in this
stage. The applications simply start, expose health endpoints, and the frontend
can confirm it can reach the Control API.

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
                     └──────────────────┘


                     ┌──────────────────┐
                     │ Monitor Worker   │
                     │ localhost:8081   │
                     └──────────────────┘
```

The Monitor Worker is currently completely independent. It does not talk to the
Control API, it holds no monitoring logic, and nothing calls it except its own
health endpoint. It exists now so that background monitoring work has a home in
later stages.

---

## Prerequisites

```text
Java 21
Node.js 18+
npm
```

Maven is **not** required — both backend projects ship the Maven Wrapper
(`./mvnw`), which downloads the correct Maven version automatically.

---

## Repository Structure

```text
pulseguard/
├── backend/
│   ├── control-api/        Spring Boot application (independent Maven project)
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

```bash
cd backend/control-api
./mvnw spring-boot:run
```

Starts on <http://localhost:8080>.

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

---

## Building the Frontend

```bash
cd frontend
npm run build
```

---

## Configuration

Both backend applications read these optional environment variables:

| Variable          | Applies to  | Default                 | Purpose                          |
| ----------------- | ----------- | ----------------------- | -------------------------------- |
| `SERVER_PORT`     | both        | `8080` / `8081`         | HTTP port                        |
| `FRONTEND_ORIGIN` | Control API | `http://localhost:5173` | Origin allowed by CORS           |

The frontend reads:

| Variable            | Default                 | Purpose               |
| ------------------- | ----------------------- | --------------------- |
| `VITE_API_BASE_URL` | `http://localhost:8080` | Control API base URL  |

No secrets exist in this stage.

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
MySQL + Flyway
Authentication
Project Management
Monitor Management
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

The next stage is **Task 02 — MySQL Database Foundation**.
