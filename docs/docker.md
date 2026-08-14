# PulseGuard — Docker Runbook

Everything needed to run the whole of PulseGuard on one machine with one
command, and everything needed to work out what has gone wrong when it does not
start.

```bash
docker compose up -d --build
```

That is the whole quick start. There is no configuration step, no database to
create by hand, and no `.env` to write first.

---

## Contents

- [What you get](#what-you-get)
- [Ports](#ports)
- [The one thing that confuses everybody: localhost](#the-one-thing-that-confuses-everybody-localhost)
- [Startup order, and why it is what it is](#startup-order-and-why-it-is-what-it-is)
- [Services](#services)
- [Profiles](#profiles)
- [Volumes and what destroys them](#volumes-and-what-destroys-them)
- [Environment variables](#environment-variables)
- [Monitoring something inside Docker](#monitoring-something-inside-docker)
- [Kafka UI](#kafka-ui)
- [Mailpit](#mailpit)
- [Lifecycle commands](#lifecycle-commands)
- [Troubleshooting](#troubleshooting)
- [Security posture](#security-posture)
- [This is development infrastructure](#this-is-development-infrastructure)

---

## What you get

```text
                                Browser
                                   │
                                   ▼
                         ┌──────────────────┐
                         │ frontend (nginx) │  :5173
                         │ · static SPA     │
                         │ · /api/* proxy ──┼──┐
                         └──────────────────┘  │
                                               ▼
                                    ┌──────────────────┐
                                    │   control-api    │  :8080
                                    │   Flyway owner   │
                                    └────────┬─────────┘
                                             │
                                             ▼
                                    ┌──────────────────┐
                    ┌──────────────▶│      mysql       │◀──────────────┐
                    │               │  :3307 → 3306    │               │
                    │               └──────────────────┘               │
                    │                                                  │
         ┌──────────┴──────────┐                    ┌──────────────────┴───┐
         │   monitor-worker    │ :8081              │ notification-service │ :8082
         │  checks, incidents  │                    │   Kafka consumer     │
         │   outbox producer   │                    │   email delivery     │
         └──────────┬──────────┘                    └───────┬──────────────┘
                    │ publishes                    consumes │
                    │           ┌──────────────────┐        │
                    └──────────▶│      kafka       │───────▶┘
                                │  :19092 → 29092  │
                                │ single-node KRaft│
                                └────────┬─────────┘
                                         │ observed by
                                         ▼
                                ┌──────────────────┐
                                │ kafka-ui (tools) │  :8090
                                └──────────────────┘

      notification-service ──SMTP──▶ mailpit :1025 ──▶ web inbox :8025
```

The architecture is unchanged from the non-Docker setup. Compose only wires
together the same four applications that already existed; no service was split,
merged, or given a new responsibility because it now runs in a container.

---

## Ports

Every published port is bound to `127.0.0.1`, so nothing here is reachable from
the rest of your network.

| What | From your machine | From inside the network |
|---|---|---|
| Frontend | `http://localhost:5173` | `frontend:80` |
| Control API | `http://localhost:8080` | `control-api:8080` |
| Monitor Worker | `http://localhost:8081` | `monitor-worker:8081` |
| Notification Service | `http://localhost:8082` | `notification-service:8082` |
| Mailpit — web inbox | `http://localhost:8025` | `mailpit:8025` |
| Mailpit — SMTP | `localhost:1025` | `mailpit:1025` |
| MySQL | `localhost:3307` | `mysql:3306` |
| Kafka | `localhost:19092` | `kafka:29092` |
| Kafka UI *(tools)* | `http://localhost:8090` | `kafka-ui:8080` |
| demo-target *(demo)* | `http://localhost:8088` | `demo-target:80` |

Two of these deliberately differ from the conventional port:

- **MySQL on 3307.** A developer machine very often already runs MySQL on 3306.
  The container keeps 3306 internally; only the published port moved.
- **Kafka on 19092.** Same reasoning — a locally installed broker usually owns
  9092.

The frontend stays on **5173**, the port the Vite dev server uses, even though
it is nginx here. That keeps bookmarks working across both workflows and keeps
the incident links inside notification emails pointing somewhere real.

---

## The one thing that confuses everybody: localhost

Inside a container, `localhost` means *that container*. It does not mean your
machine, and it does not mean another service.

```text
From your machine (host)      →  localhost:<published port>
From inside a container       →  <service-name>:<container port>
```

Worked examples:

| You want | From the host | From a container |
|---|---|---|
| the database | `localhost:3307` | `mysql:3306` |
| the broker | `localhost:19092` | `kafka:29092` |
| the Control API | `localhost:8080` | `control-api:8080` |
| the mail server | `localhost:1025` | `mailpit:1025` |

This matters most when creating a monitor. A monitor URL of
`http://localhost:8080/actuator/health` is evaluated **by the Monitor Worker**,
so it means "the Monitor Worker's own port 8080" — where nothing is listening.
To monitor the Control API from inside Compose, use
`http://control-api:8080/actuator/health`.

Reaching a service running on your *host* from inside a container needs
`host.docker.internal`, which behaves differently across platforms. It is not
the recommended way to demo PulseGuard — use the [demo profile](#profiles)
instead.

---

## Startup order, and why it is what it is

Ordering is expressed with health conditions, not sleeps. Compose waits for a
service to report healthy before starting whatever depends on it.

```text
mysql  (healthy: a real SELECT 1 as the app user)
  │
  ▼
control-api  ── runs Flyway V1–V8 ──▶ healthy
  │
  ├──────────────┬──────────────────┐
  ▼              ▼                  ▼
monitor-worker   notification-service   frontend
(also waits on kafka healthy)          (nginx proxy needs
                                        a real upstream)
```

**Why the worker and the notification service wait for the Control API.**
Neither of them calls it. They wait because the Control API is the sole Flyway
owner: it creates every table in the database. Both other services run with
`ddl-auto=validate`, which compares their JPA mappings against the live schema
at startup and refuses to start if anything is missing. Starting them against a
half-migrated database would be a guaranteed crash on a fresh volume. Gating on
the Control API's health turns that race into a sequence.

**Why Mailpit is only `service_started`.** The Notification Service is designed
to survive an unreachable mail server — deliveries are written to the database
and retried on a schedule. Making SMTP a readiness gate would assert the
opposite of what the design says. It is also why
`management.health.mail.enabled=false` remains set: an SMTP outage must not mark
the service DOWN and get a healthy process restarted.

**Health checks in use:**

| Service | Probe |
|---|---|
| mysql | `mysql -h 127.0.0.1 -u<app user> -e 'SELECT 1' pulseguard` |
| kafka | `/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list` |
| control-api | `curl -fsS http://127.0.0.1:8080/actuator/health` |
| monitor-worker | `curl -fsS http://127.0.0.1:8081/actuator/health` |
| notification-service | `curl -fsS http://127.0.0.1:8082/actuator/health` |
| frontend | `wget -q -O /dev/null http://127.0.0.1/` |

The MySQL probe is forced onto TCP (`-h 127.0.0.1`) on purpose. The MySQL image
runs a temporary socket-only server while it initialises the data directory, and
a socket-based probe reports success against *that* — seconds before the real
server is accepting connections.

The probes address `127.0.0.1` rather than `localhost` for a related reason. In
the frontend container busybox `wget` resolves `localhost` to `::1` first, and
that nginx listens on IPv4 only — so the name form failed with "connection
refused" against a server that was serving perfectly well, and the container sat
at `health: starting` indefinitely. Naming the address leaves no room for the
resolver to pick the wrong family.

The Kafka probe uses the broker's own CLI, already present in the image, rather
than adding a container just to probe one.

### Startup is slow, and the timings are sized for that

Measured on the development machine this was built on — an older Intel Mac
running Docker Desktop, so the JVM and MySQL are both working through a VM:

| | |
|---|---|
| MySQL first-ever start (initialising an empty data directory) | ~95 s |
| MySQL start on an existing volume | ~40 s |
| Control API cold start | **229 s** |
| One `kafka-topics.sh` probe (JVM startup dominates) | ~22 s idle, ~39 s under load |
| `docker compose up -d` → every core service healthy | **~5 min** |

Those numbers drive three deliberate choices:

- **`start_period` is generous** (300 s for the Java services, 180 s for MySQL,
  120 s for Kafka). A failing probe *inside* `start_period` does not count
  against `retries`, so a wide window costs nothing on a fast machine — it is
  simply never reached. Too narrow a window, though, starts counting real
  failures against a service that is merely still booting: an earlier 180 s
  setting was already being exceeded by the Control API's 229 s start.
- **`start_interval` keeps the gate responsive.** Probes run every few seconds
  while a container is starting and then settle to the slow steady-state
  `interval`, so dependencies are released promptly without probing forever
  afterwards.
- **Kafka's steady-state `interval` is 120 s with a 60 s `timeout`.** Its probe
  costs a whole JVM start; at a shorter interval the broker would spend a
  substantial fraction of its time checking on itself.

None of this is a property of PulseGuard — it is the cost of a laptop. These
values should be tightened considerably for real hardware, and the same
applications start in a fraction of the time outside a Docker Desktop VM.

### How much memory the stack wants

`docker stats --no-stream` with everything running, including both optional
profiles — a **development baseline only**, not a sizing recommendation:

| Container | Memory |
|---|---|
| control-api | ~450 MiB |
| monitor-worker | ~440 MiB |
| notification-service | ~450 MiB |
| kafka | ~430 MiB |
| mysql | ~400 MiB |
| kafka-ui *(tools)* | ~360 MiB |
| mailpit | ~19 MiB |
| frontend | ~5 MiB |
| demo-target *(demo)* | ~5 MiB |
| **Total** | **~2.5 GiB** |

The measurement above was taken against a Docker Desktop VM allocated 3.8 GiB,
which leaves little headroom and is a large part of why startup is slow. **If
the stack feels sluggish, give Docker Desktop more memory** (Settings →
Resources) before assuming something is wrong — 6–8 GiB is comfortable.

### Kafka UI can saturate a small VM

Worth knowing before you leave the `tools` profile running: on this machine
`kafka-ui` was observed sitting at **~300% CPU** while apparently idle. On a VM
with few cores that is enough to starve everything else, and it produced a
genuine cascade:

```text
kafka-ui pegs the CPU
        │
        ├──▶ kafka's health probe (a whole JVM) overruns its 60s timeout
        │    three times → kafka marked unhealthy
        │
        └──▶ control-api's TLS handshake to MySQL is starved and breaks
             ("Communications link failure ... Broken pipe")
             → Flyway cannot connect → the application exits
             → monitor-worker and notification-service never start,
               because they gate on control-api being healthy
```

Nothing was misconfigured; the machine simply ran out of CPU. Stopping
`kafka-ui` dropped total CPU immediately and the Kafka probe went back to
finishing in ~28 seconds, after which the stack started normally.

So: **treat `tools` as something you turn on to look at Kafka and turn off
again**, especially on a small VM.

```bash
docker compose stop kafka-ui      # when you are done inspecting
```

If you see MySQL connection failures, an unhealthy Kafka, or services stuck in
`Created`, check `docker stats` before suspecting the configuration — on a
constrained VM this is the far more likely explanation.

No CPU or memory limits are set in `compose.yaml` for it. Capping a development
tool based on one laptop's numbers would be guesswork; knowing to stop it is
more useful than a limit that might be wrong elsewhere.

No memory limits are set in `compose.yaml`. Hard limits based on one laptop's
observations would be guesswork; the JVMs are told to keep to 75% of whatever
they are given (`-XX:MaxRAMPercentage=75.0`), which is the part that matters.

---

## Services

| Service | Image / build | Host port | Depends on | Purpose |
|---|---|---|---|---|
| `mysql` | `mysql:8.0.46` | 3307 → 3306 | — | The single shared database |
| `kafka` | `apache/kafka:4.3.1` | 19092 | — | Single-node KRaft broker |
| `mailpit` | `axllent/mailpit:v1.30.7` | 8025, 1025 | — | Development SMTP sink |
| `control-api` | build `backend/control-api` | 8080 | mysql *(healthy)* | Config/query plane, Flyway owner |
| `monitor-worker` | build `backend/monitor-worker` | 8081 | mysql, kafka, control-api *(all healthy)* | Checks, incidents, outbox producer |
| `notification-service` | build `backend/notification-service` | 8082 | mysql, kafka, control-api *(healthy)*; mailpit *(started)* | Kafka consumer, email delivery |
| `frontend` | build `frontend` | 5173 → 80 | control-api *(healthy)* | Static SPA + `/api` reverse proxy |
| `kafka-ui` | `ghcr.io/kafbat/kafka-ui:v1.5.0` | 8090 → 8080 | kafka *(healthy)* | **Profile `tools`** — topic/message browser |
| `demo-target` | `nginx:1.29-alpine` | 8088 → 80 | — | **Profile `demo`** — a target you can switch off |

Local image names are `pulseguard-control-api:local`,
`pulseguard-monitor-worker:local`, `pulseguard-notification-service:local` and
`pulseguard-frontend:local`. No registry is involved; nothing is pushed.

### Images

**Backend (all three, same pattern).** Multi-stage:
`eclipse-temurin:21-jdk-jammy` builds with the service's *own* `./mvnw`, and
only the resulting Spring Boot jar is copied into an
`eclipse-temurin:21-jre-jammy` runtime stage. No source, no Maven cache, no test
classes and no JDK compiler reach the final image. `curl` is installed for the
health probe. Each runs as a non-root `pulseguard` user (uid 1001).

Tests are **not** re-run during the image build. `./mvnw verify` on a developer
machine, and later CI, is the quality gate; repeating the suites in every
`docker compose build` would add minutes and report a failing test as a Docker
build error.

**Frontend.** `node:22-alpine` runs `npm ci` (lockfile-exact, not
`npm install`) and `npm run build`, which is `tsc -b && vite build` — so a type
error fails the image build. Only `dist/` is copied into an `nginx:1.29-alpine`
runtime stage. No `node_modules`, no TypeScript compiler, no Vite dev server.

### The frontend's two jobs

nginx serves the built SPA and also reverse-proxies the API:

```nginx
location /api/ {
    set $control_api http://control-api:8080;
    proxy_pass $control_api$request_uri;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

The `try_files` fallback is what makes a browser refresh work on
`/monitors/14`, `/projects/1/dashboard` or `/incidents/5`. Those are React
Router routes, not files; without the fallback nginx would answer 404.

The proxy exists so the browser only ever talks to one origin. The alternative
would be to bake `http://control-api:8080` into the JavaScript bundle — a name
that resolves only *inside* the Compose network, which is the one place the
browser is not. So the image is built with `VITE_API_BASE_URL=""`, the bundle
requests `/api/v1/...` with no host, and those requests are same-origin. Backend
CORS is left exactly as it was; nothing was widened to `*` to make Compose work.

Running `npm run dev` on the host is unchanged and still uses
`VITE_API_BASE_URL=http://localhost:8080`.

The upstream is held in a variable rather than written literally into
`proxy_pass`. A literal upstream is resolved once when nginx loads its
configuration and then cached for the life of the process, so if the Control
API container were ever replaced on a different IP the proxy would be left
talking to an address nothing answers on — and restarting the backend again
would not help. Going through a variable forces re-resolution against Docker's
embedded DNS on a short TTL.

This one is insurance rather than a fix for something observed: recreating the
Control API container during testing happened to hand it the same IP back each
time, so the cached-address failure was never actually provoked. The proxy was
confirmed working after the container was recreated.

---

## Profiles

The default stack is the seven core services. Two optional profiles add
development tooling.

```bash
# Core stack only
docker compose up -d --build

# Core + Kafka UI
docker compose --profile tools up -d --build

# Core + Kafka UI + a monitor target you can switch off
docker compose --profile tools --profile demo up -d --build
```

| Profile | Adds | For |
|---|---|---|
| *(default)* | mysql, kafka, mailpit, control-api, monitor-worker, notification-service, frontend | Running PulseGuard |
| `tools` | `kafka-ui` | Inspecting topics, partitions, offsets, keys and payloads |
| `demo` | `demo-target` | A disposable HTTP endpoint to monitor, stop and restart |
| `quality` | `sonarqube` | Static analysis and coverage — see [docs/sonarqube.md](sonarqube.md) |
| `ci` | `jenkins` | The build pipeline — see [docs/jenkins.md](jenkins.md) |

No profile is required for PulseGuard to work, and nothing in the application
knows they exist. There are no Kafka UI links in the React application, no
PulseGuard service depends on `demo-target`, and **nothing depends on SonarQube
or Jenkins** — no `depends_on`, no runtime relationship of any kind.

### The ci profile

```bash
docker compose --profile ci up -d --build jenkins
```

**Name the service.** `docker compose --profile ci up -d` without it would also
start the entire PulseGuard runtime stack, because the core services carry no
profile and are therefore always eligible. The pipeline needs none of them —
the suites it runs require no MySQL, no Kafka and no SMTP.

Jenkins and SonarQube are independent profiles and can be started together when
you want a Sonar-enabled build:

```bash
docker compose --profile ci --profile quality up -d jenkins sonarqube
```

Inside the Compose network Jenkins reaches SonarQube at `http://sonarqube:9000`
— `localhost` there would be Jenkins itself. Humans still use
`http://localhost:9000`.

There is deliberately **no Docker socket mount and no privileged flag** on the
Jenkins container. The pipeline builds no images, so it has no need to reach the
Docker daemon, and cannot.

### The quality profile

```bash
docker compose --profile quality up -d sonarqube
```

Deliberately started on its own rather than alongside the stack. SonarQube runs
an embedded Elasticsearch, is slow to start and is one of the heaviest things
here — and the scanners read the source tree from your machine, so **the
PulseGuard stack does not need to be running at all** to analyse the code.
Running only the `sonarqube` container is the lightest way to work.

If your machine is constrained, do not run this and `kafka-ui` at the same
time:

```bash
docker compose stop kafka-ui
```

SonarQube stores its analysis in its own volumes (`sonarqube_data`,
`sonarqube_extensions`, `sonarqube_logs`) using an embedded database that has
**nothing to do with PulseGuard's MySQL**. Resetting SonarQube by name rather
than with `down -v` is covered in [docs/sonarqube.md](sonarqube.md#stopping-and-resetting).

---

## Volumes and what destroys them

```text
mysql_data              →  /var/lib/mysql          all PulseGuard data
kafka_data              →  /var/lib/kafka/data     topic metadata and messages

sonarqube_data          →  /opt/sonarqube/data     analysis history + embedded DB
sonarqube_extensions    →  /opt/sonarqube/extensions
sonarqube_logs          →  /opt/sonarqube/logs

jenkins_home            →  /var/jenkins_home       admin user, job, credentials, history
```

The SonarQube and Jenkins volumes only exist once their profiles have been
started, and hold nothing belonging to PulseGuard. Reset either one **by name**
rather than with `down -v`, which would take MySQL and Kafka with it — see
[docs/sonarqube.md](sonarqube.md#stopping-and-resetting) and
[docs/jenkins.md](jenkins.md#persistence-and-resetting).

| Command | Containers | `mysql_data` | `kafka_data` |
|---|---|---|---|
| `docker compose stop` | stopped | kept | kept |
| `docker compose down` | removed | **kept** | **kept** |
| `docker compose down -v` | removed | **DELETED** | **DELETED** |

`docker compose down -v` is not "stop the application". It permanently deletes
the local Docker database — every user, project, monitor, check, incident,
outbox row and notification record — and the Kafka log directory with it. The
next `up` starts from an empty database, Flyway re-applies V1–V8, and you
register your account again.

It does **not** touch a MySQL server installed on your machine outside Docker.
Those are entirely separate; the Compose database lives only in the
`pulseguard_mysql_data` Docker volume.

Mailpit deliberately has no volume: captured mail is in-memory and is gone after
`docker compose down`. It is a development sink, not an archive.

---

## Environment variables

Every value has a working default in `compose.yaml`, so nothing has to be set.
`.env.compose.example` documents them all; copy it to `.env` at the repository
root (Compose reads that automatically, and it is gitignored) to override
anything.

**Development credentials, stated plainly.** The database passwords and the JWT
secret in `compose.yaml` are throwaway laptop values, committed on purpose so
the stack starts with no setup. They are not deployment credentials. Real
secrets arrive with the deployment stages, from a secret store — and the JWT
secret in particular is passed as runtime environment configuration, never baked
into an image.

### Control API

| Variable | Compose value | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:mysql://mysql:3306/pulseguard?connectionTimeZone=UTC&preserveInstants=true` | Database |
| `DB_USERNAME` | `pulseguard` | Application user — never root |
| `DB_PASSWORD` | *(dev value)* | |
| `JWT_SECRET` | *(dev value)* | Token signing key |
| `FRONTEND_ORIGIN` | `http://localhost:5173` | CORS allow-list |

### Monitor Worker

| Variable | Compose value | Meaning |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | as above | Same database |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` | Internal listener |
| `MONITOR_ALLOW_PRIVATE_ADDRESSES` | `true` | **Development only** — see below |

### Notification Service

| Variable | Compose value | Meaning |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | as above | Same database |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` | Internal listener |
| `MAIL_HOST` | `mailpit` | SMTP host |
| `MAIL_PORT` | `1025` | SMTP port |
| `MAIL_FROM` | `pulseguard@localhost` | Envelope sender |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | Base for incident links **in email** |

`FRONTEND_BASE_URL` is a browser-facing address on purpose. The reader opens
the link from their own machine, so `http://frontend` — which resolves only
inside the Compose network — would be a dead link in every email.

### Frontend

| Variable | Compose value | Meaning |
|---|---|---|
| `VITE_API_BASE_URL` | `""` *(build argument)* | Empty = same-origin, proxied by nginx |

### Compose infrastructure

`MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`,
`MYSQL_HOST_PORT`, `KAFKA_HOST_PORT`, `KAFKA_CLUSTER_ID`, `MAILPIT_UI_PORT`,
`MAILPIT_SMTP_PORT`, `KAFKA_UI_PORT`, `DEMO_TARGET_PORT`, and the per-service
port overrides. All documented in `.env.compose.example`.

---

## Monitoring something inside Docker

### The private-address override

PulseGuard sends HTTP requests to addresses its users supply, which makes it a
potential SSRF tool. `DestinationPolicy` therefore refuses private, loopback,
link-local and similar destinations by default, judging the **resolved
addresses** rather than the host string.

Every address inside a Compose network is private (`172.16.0.0/12`). So with the
default policy the worker would refuse to monitor anything in this stack — not
`demo-target`, not `control-api`, nothing. Compose therefore sets:

```text
MONITOR_ALLOW_PRIVATE_ADDRESSES=true
```

**This is local Compose development only, and must never become a deployed
default.** With it on, anyone who can create a monitor can point PulseGuard at
anything the worker can reach on the network. That is acceptable on a laptop
where the network holds only this stack, and unacceptable anywhere else.

**Cloud metadata endpoints stay blocked regardless of this setting.**
`169.254.169.254`, `fd00:ec2::254` and `100.100.100.200` are in an unconditional
block list that the private-address rule does not reach, because those addresses
hand out instance credentials to anything that can talk to them.

### The demo target

```bash
docker compose --profile demo up -d
```

Create a monitor with:

```text
URL:                    http://demo-target/
Expected HTTP status:   200
Interval:               30 seconds (or whatever suits your patience)
Failure threshold:      3
```

Then cause a genuine outage — not a simulated one, a real connection failure:

```bash
docker compose stop demo-target      # checks start failing
docker compose start demo-target     # checks recover
```

Watch it happen:

```bash
docker compose logs -f monitor-worker        # checks, DOWN/UP, incidents
docker compose logs -f notification-service  # consumed events, deliveries
open http://localhost:8025                   # the emails
open http://localhost:8090                   # the Kafka messages (tools profile)
```

**Expect `DNS_ERROR`, not `CONNECTION_ERROR`.** Stopping a container removes its
name from Docker's DNS, so `demo-target` stops *resolving* rather than
refusing connections, and the recorded failure reads:

```text
Error: DNS_ERROR
Details: DNS resolution failed
```

This is a real network failure and drives the incident exactly like any other,
but it differs from stopping a process on a host — which leaves the name
resolving and produces a connection refusal instead. Both paths are handled;
only the recorded `errorType` differs.

### Monitoring the stack itself

`http://control-api:8080/actuator/health` works as a monitor URL and is a
reasonable thing to watch. Remember it is the *worker* that resolves the name.

---

## Kafka UI

```bash
docker compose --profile tools up -d
open http://localhost:8090
```

One cluster is preconfigured, named **PulseGuard Local**, pointing at
`kafka:29092` — the internal listener, because Kafka UI runs inside the network
like any other container. Pointing it at `localhost:19092` would mean the Kafka
UI container itself.

Navigate to **Topics → `pulseguard.incident-events.v1`** to see partitions,
offsets, message keys and JSON payloads, and **Consumers →
`pulseguard-notification-service-v1`** for consumer lag.

The topic is created by the Monitor Worker through Spring Kafka, with three
partitions, keyed by monitor id. Kafka UI only observes it — there is no second
topic-creation mechanism, and the Control API declares nothing.

Broker auto-topic-creation is deliberately **off**. With it on, whichever client
asked for the topic first would win the race and create it with a single
partition, and the worker's declaration cannot widen an existing topic
afterwards.

---

## Mailpit

```bash
open http://localhost:8025
```

Mailpit accepts any SMTP conversation and delivers nothing anywhere. It replaces
the throwaway Python debug SMTP server used before Docker, and it shows the real
rendered message rather than a console dump.

The Notification Service reaches it at `mailpit:1025` with auth and TLS off. An
incident cycle produces two emails:

```text
[PulseGuard] Incident opened: <monitor name>
[PulseGuard] Incident resolved: <monitor name>
```

Each contains a link to `http://localhost:5173/incidents/{id}` — a host address,
so it opens correctly from your browser.

**Mailpit is not a production email provider.** It is development
infrastructure with no delivery, no authentication and no retention.

```text
Local Docker development:   Notification Service ──▶ Mailpit
Future AWS:                 Notification Service ──▶ production SMTP / SES
```

---

## Lifecycle commands

```bash
# Start (build images if needed)
docker compose up -d --build

# What is running, and is it actually healthy?
docker compose ps

# Logs
docker compose logs -f                       # everything
docker compose logs -f control-api           # one service
docker compose logs -f monitor-worker
docker compose logs -f notification-service
docker compose logs -f kafka

# Pause and resume without losing containers
docker compose stop
docker compose start

# Restart one service
docker compose restart monitor-worker

# Rebuild after a code change
docker compose up -d --build control-api

# Remove containers and the network. Volumes survive.
docker compose down

# Remove containers, network AND all local Docker data.  ⚠ DESTRUCTIVE
docker compose down -v
```

Read `docker compose ps` rather than assuming: a container can be `running` and
still `starting` or `unhealthy`. The `STATUS` column shows both.

### What survives a restart

Restarting containers is safe, and the outbox and inbox patterns are what make
it so. Verified behaviour:

| You restart | What happens |
|---|---|
| `monitor-worker` / `notification-service` | Checks resume. **No duplicate incidents and no repeat emails** — the inbox recognises any event Kafka redelivers. |
| `kafka` | The worker keeps checking and keeps opening incidents; their events queue in `outbox_events` with `published_at` still null. When the broker returns the publisher drains the backlog, the consumer picks it up, and the delayed emails go out. Nothing is lost. |
| `mysql` | Database calls fail while it is away. All three services reconnect on their own once it is back — Hikari replaces the dead connections — and no service needed restarting. |
| `control-api` | The frontend keeps proxying to it once it is back. nginx re-resolves the upstream name rather than caching an address, so a replacement container is followed even if Docker gives it a different IP. |

The one thing to keep in mind is that a Kafka outage delays notifications; it
does not cancel them. Expect the queued emails to arrive together when the
broker comes back.

All services log to stdout/stderr and are read with `docker compose logs`. No
service writes log files inside its container, and there are no bind-mounted log
directories.

---

## Troubleshooting

**A port is already in use.** Something on your machine owns it. Find it with
`lsof -i :5173` (or the relevant port) and either stop it or override the port
in `.env`. MySQL and Kafka are already moved to 3307 and 19092 for this reason.

**`control-api` is unhealthy and the log says Flyway or validation failed.** The
schema and the entity mappings disagree. On a fresh volume this should not
happen; if you have an old volume from an earlier schema, `docker compose down
-v` and start again — but read the volume warning above first.

**`monitor-worker` or `notification-service` exits at startup with a schema
validation error.** They started against a database the Control API had not
finished migrating. Check `docker compose ps` — the Control API should be
`healthy`, not merely `running`. If the Control API is genuinely failing, fix
that first; these two are downstream of it.

**Every monitor reports "Destination blocked by monitoring security policy".**
`MONITOR_ALLOW_PRIVATE_ADDRESSES` is not reaching the worker. Confirm with
`docker compose config | grep -A2 MONITOR_ALLOW`.

**A monitor on `http://localhost:...` always fails.** Inside the worker,
`localhost` is the worker. Use the service name — see
[localhost](#the-one-thing-that-confuses-everybody-localhost).

**No emails in Mailpit.** Follow the chain rather than guessing: is there an
incident (`docker compose logs monitor-worker | grep -i incident`), did the
event reach Kafka (Kafka UI, or `docker compose logs monitor-worker | grep -i
outbox`), was it consumed (`docker compose logs notification-service`), and does
the project have enabled members with email addresses? A project with no
recipients records the event as processed and sends nothing, by design.

**Kafka will not start after changing `KAFKA_CLUSTER_ID`.** The id is recorded
in the log directory and cannot be changed under an existing `kafka_data`
volume. Change it only together with `docker compose down -v`.

**Several services fail at once — MySQL "Communications link failure", Kafka
unhealthy, containers stuck in `Created`.** Check `docker stats` first. This
pattern is usually CPU starvation on a small Docker VM rather than a
misconfiguration, and `kafka-ui` is the usual cause — see
[Kafka UI can saturate a small VM](#kafka-ui-can-saturate-a-small-vm). Stop the
`tools` profile, give Docker more resources, and start again.

**The frontend loads but every API call fails.** Check the Control API is
healthy, then `docker compose logs frontend` for nginx proxy errors. Browser
requests should go to `http://localhost:5173/api/v1/...` — same origin. If you
see requests to `http://control-api:8080`, the image was built with a
`VITE_API_BASE_URL` it should not have.

---

## Security posture

What this Compose stack does, and does not, do:

- **Backend containers run as a non-root `pulseguard` user** (uid 1001). Nothing
  in these applications needs root.
- **Published ports are bound to `127.0.0.1`**, so the stack is not exposed to
  your LAN.
- **No privileged containers.** No `privileged: true` anywhere.
- **No Docker socket is mounted.** No container can talk to the Docker daemon.
- **No host networking.** Service-to-service traffic goes over one private
  Compose network, `pulseguard-network`, using DNS service names. There is not a
  single container IP address in `compose.yaml`.
- **No production credentials in any image.** The JWT secret and database
  passwords are runtime environment configuration with development-only values.
- **No `container_name`.** Compose manages names, so nothing is pinned in a way
  that would block running more than one replica later.
- **The private-address monitoring override is development-only** and documented
  as such above.

Honest limitations of the same list:

- The nginx container follows the official image's model: the master process
  starts as root and workers drop to the `nginx` user. The Java containers are
  fully non-root; this one is not.
- Kafka runs plaintext with no authentication. Fine on a loopback-bound laptop
  network; not a deployment model.
- MySQL connections *are* encrypted — all 30 application connections were
  observed on TLSv1.3 — but with the certificate the MySQL image generates for
  itself, which the driver does not verify. That is encryption without
  authentication: it protects against passive sniffing and not against an
  active man in the middle. Adequate for a private Compose network, and not a
  substitute for a real certificate later.
- Base images are pinned by tag, not by digest. Digest pinning belongs with a
  registry, which arrives in a later stage.

---

## This is development infrastructure

The infrastructure choices here exist to make one machine reproducible. They do
**not** describe the eventual AWS architecture:

| Compose (development) | Later (AWS) |
|---|---|
| MySQL in a container | Amazon RDS for MySQL |
| Single-node Kafka, no auth, no TLS | Managed/replicated Kafka |
| Mailpit | Production SMTP / SES |
| Kafka UI | No production role whatsoever |
| One replica of each service | Multiple replicas, horizontally scaled |
| Development credentials in `compose.yaml` | Secret store |

Docker is also **not** mandatory. The existing workflow — local MySQL, local
Kafka, Spring Boot from IntelliJ, `npm run dev` — is unchanged and is still the
faster loop for ordinary development and for running the test suites. See the
README for that path. Compose is an additional, reproducible way to run
everything at once.

Consistent with the Task 11 testing decision, none of this is covered by
automated integration tests. There are no Testcontainers, no H2 and no embedded
Kafka anywhere in the project; the automated suites stay infrastructure-free and
fast, and Docker is verified by running it.
