# PulseGuard — SonarQube Runbook

Static analysis and test coverage for the three PulseGuard backend
applications, running locally. (The frontend is deliberately excluded — see
[below](#the-frontend-is-not-analysed-by-sonarqube).)

```bash
docker compose --profile quality up -d sonarqube   # wait a few minutes
export SONAR_TOKEN=<token generated in the UI>
./scripts/sonar-local.sh
```

---

## Contents

- [What this is for](#what-this-is-for)
- [The quality policy, stated plainly](#the-quality-policy-stated-plainly)
- [Starting SonarQube](#starting-sonarqube)
- [First login and a token](#first-login-and-a-token)
- [The projects](#the-projects)
- [Java coverage](#java-coverage)
- [Frontend coverage](#frontend-coverage)
- [Running the scans](#running-the-scans)
- [What the coverage number actually measures](#what-the-coverage-number-actually-measures)
- [Coverage exclusions](#coverage-exclusions)
- [Reading the results](#reading-the-results)
- [Stopping and resetting](#stopping-and-resetting)
- [Troubleshooting](#troubleshooting)
- [Later, in CI](#later-in-ci)

---

## What this is for

To see, in one place, what the codebase currently looks like: bugs,
vulnerabilities, security hotspots, code smells, duplication and unit-test
coverage. That is the whole goal — a baseline, and a dashboard worth glancing
at.

SonarQube here is **development tooling**, in the same category as Kafka UI. It
reads the source tree. It is not part of the architecture:

- no PulseGuard service has `depends_on: sonarqube`
- SonarQube being stopped, broken or never started has **zero** effect on the
  Control API, Monitor Worker, Notification Service or frontend
- `docker compose up -d` does not start it — it lives behind the `quality`
  profile
- `./mvnw clean verify` never contacts it

---

## The quality policy, stated plainly

This is deliberate, and it is worth being explicit because the defaults invite
the opposite:

- **No minimum coverage threshold** is configured. Not 80%, not 50%, not any
  number. The question being answered is "what is the coverage?", not "does it
  pass?".
- **No custom Quality Gate.** SonarQube's default gate applies, untouched.
- **Nothing blocks a build.** `sonar.qualitygate.wait` is not set, so a scan
  uploads its analysis and finishes.
- **A failing Quality Gate is not a failure of the setup.** If the default gate
  reports FAILED, that is information about the code, and the response is to
  read it — not to edit the gate until the badge turns green.

Targets may be introduced later if there is a real reason. There is no reason
yet.

---

## Starting SonarQube

```bash
docker compose --profile quality up -d sonarqube
```

Then wait — properly. SonarQube runs an embedded Elasticsearch, and the **first**
start on an empty volume also applies 72 database migrations and registers the
rule set for every bundled language. Measured on the development machine this
was built on:

```text
container started            10:14:28
web server up                10:27:03      ← 12 min 35 s
```

Later starts, with the volume already populated, are much quicker. The health
check allows 15 minutes before it starts counting failures, so a slower machine
still has room.

Watch it rather than guessing:

```bash
docker compose ps            # look for (healthy), not merely "Up"
docker compose logs -f sonarqube
```

The health check polls SonarQube's own `/api/system/status`, which reports `UP`
only once the web server, compute engine and search index are all ready — well
after port 9000 starts accepting connections.

Once healthy: **<http://localhost:9000>**

The port is bound to `127.0.0.1`, so the instance is not reachable from the rest
of your network.

### It is heavy — do not run everything at once

SonarQube is the heaviest thing in `compose.yaml`. Measured on the development
machine (`docker stats`, 3.8 GiB Docker VM):

| Phase | CPU | Memory |
| --- | --- | --- |
| Starting up | ~400–1000% | 1.0–1.6 GiB |
| Running an analysis | ~1140% | ~2.2 GiB |
| Settled after startup | ~380% | ~2.1 GiB |

A **development baseline only**, not a sizing recommendation — but it is why
SonarQube and Kafka UI should not be running at once on a constrained laptop:

```bash
docker compose stop kafka-ui       # before starting SonarQube
docker compose stop sonarqube      # when you are done reading the dashboard
```

You do not need the PulseGuard stack running at all to analyse the code. The
scanners run on your machine, read the source and the coverage reports, and talk
only to SonarQube. Analysing with **only** the `sonarqube` container up is the
lightest way to work.

### The database

SonarQube Community Build stores its analysis in an **embedded H2 database**
inside the `sonarqube_data` volume. That is fine for a local instance and is
what keeps this setup simple — no PostgreSQL container was added just to
support a development tool.

SonarQube says so itself on every start, and the warning is accurate:

```text
WARN  web[][o.s.db.dialect.H2] H2 database should be used for evaluation purpose only.
```

Read that as intended: this instance is for looking at PulseGuard's code on a
laptop. A SonarQube anyone depended on would run against a real external
database.

> **This has nothing to do with PulseGuard's database.** PulseGuard uses MySQL,
> as it always has. SonarQube's storage is a separate volume, a separate
> engine, and holds only analysis history. A deployed SonarQube would use a
> proper external database; this one is a local quality tool and nothing more.

---

## First login and a token

On first start, log in with SonarQube's default credentials:

```text
username: admin
password: admin
```

SonarQube forces a password change immediately. Choose your own — **do not
commit it anywhere**, and note that nothing in this repository contains it.

Then generate an analysis token:

```text
My Account  →  Security  →  Generate Tokens
Type:  Global Analysis Token
```

Copy it once (SonarQube will not show it again) and export it:

```bash
export SONAR_TOKEN=<the token>
export SONAR_HOST_URL=http://localhost:9000   # optional, this is the default
```

The token is a credential. It is never written to `pom.xml`, `package.json`,
`compose.yaml` or any script — every one of those reads it from the
environment. Neither the token nor the admin password appears in any tracked
file.

---

## The projects

One Sonar project per backend, mirroring the repository exactly:

| Project key | Name | Source |
| --- | --- | --- |
| `pulseguard-control-api` | PulseGuard Control API | `backend/control-api` |
| `pulseguard-monitor-worker` | PulseGuard Monitor Worker | `backend/monitor-worker` |
| `pulseguard-notification-service` | PulseGuard Notification Service | `backend/notification-service` |

There is no monorepo aggregation and no shared Maven parent — the three backends
are separate Maven projects by design, and Sonar is configured to match rather
than to fight that.

### The frontend is not analysed by SonarQube

Deliberately, and for a specific reason. SonarQube's JavaScript/TypeScript
analyzer refuses to run on the Node version this project uses:

```text
NodeCommandException: Unsupported Node.JS version detected 18.20.4.
Please upgrade to the latest Node.JS LTS version.
```

Upgrading Node is out of scope: the application, its build and its test suite
are all pinned to the current toolchain, and raising the Node requirement to
satisfy a reporting tool would be the tail wagging the dog. So the frontend is
left out of SonarQube rather than the project being changed to suit it.

**Frontend coverage still works and is still worth running** — it is only the
SonarQube upload that is skipped:

```bash
cd frontend
npm run test:coverage      # per-file table in the terminal + coverage/lcov.info
```

To bring the frontend into SonarQube later, the only blocker is the Node
version. Once the project moves to a supported LTS, it needs a
`sonar-project.properties` naming `sonar.sources=src`, the test inclusions
(`src/**/*.test.ts`, `src/**/*.test.tsx`) and
`sonar.javascript.lcov.reportPaths=coverage/lcov.info` — the LCOV report it
would read is already being produced.

---

## Java coverage

JaCoCo, behind an **opt-in Maven profile**:

```bash
./mvnw clean verify              # normal build — no coverage, unchanged
./mvnw clean verify -Pcoverage   # adds the JaCoCo agent and writes the report
```

The report lands at the JaCoCo standard path:

```text
backend/<service>/target/site/jacoco/jacoco.xml
```

Keeping coverage out of the default build means the everyday edit–test loop pays
nothing for a report nobody is reading at the time. Each POM points Sonar at the
XML through `sonar.coverage.jacoco.xmlReportPaths`.

The scanner version is pinned in each POM's `pluginManagement`, so
`./mvnw sonar:sonar` uses a known version rather than whatever is newest. It is
declared there rather than in `plugins` precisely so it is never bound to a
build phase.

---

## Frontend coverage

Vitest with the V8 provider. This runs and is useful on its own, even though
the result is not uploaded to SonarQube:

```bash
npm run test:coverage        # vitest run --coverage
```

Prints a per-file table in the terminal and writes `frontend/coverage/lcov.info`.
`coverage.all` is enabled so files that no test imports are still reported —
otherwise an entirely untested module would be absent from the report rather
than scored zero, which flatters the total.

---

## Running the scans

The helper script does all three backends:

```bash
export SONAR_TOKEN=<token>
./scripts/sonar-local.sh              # all three
./scripts/sonar-local.sh control-api  # just one
```

It checks that `SONAR_TOKEN` is set and that SonarQube is reachable, then runs
coverage and analysis for each project. It does **not** read the Quality Gate
and does not fail on findings — it fails only if a command genuinely fails
(a broken test, or an unreachable scanner).

By hand, each backend is two commands:

```bash
cd backend/control-api
./mvnw clean verify -Pcoverage
./mvnw sonar:sonar \
  -Dsonar.host.url=$SONAR_HOST_URL \
  -Dsonar.token=$SONAR_TOKEN
```

No npm scanner is installed. Beyond the Node version problem above, adding
`@sonar/scan` to `devDependencies` pulled in two HIGH-severity advisories
(`adm-zip`, `axios`) and would have made the frontend Docker build install a
tool it never runs — so there was no reason to keep it.

---

## What the coverage number actually measures

This matters for reading the dashboard honestly.

**Included** — the fast automated suites that run with no infrastructure:

- unit tests
- service-layer tests
- controller / `@WebMvcTest` tests
- security and authorization tests
- event parsing and processing tests
- frontend component, hook and utility tests

**Not included** — deliberately, and unchanged from the Stage 11 decision:

- MySQL integration
- Kafka integration
- SMTP delivery
- Docker / Compose behaviour

There are no Testcontainers, no H2, no embedded Kafka and no embedded SMTP
anywhere in this project. Those interactions are verified by **running the real
thing manually** and recording what happened — see
[`docs/docker.md`](docker.md) and [`docs/testing-hardening.md`](testing-hardening.md).

So the coverage figure describes how well the fast suites exercise the
application's own logic. It is not a claim about the system end to end, and it
was not raised by writing tests that assert nothing.

---

## Coverage exclusions

Kept conservative, and applied as **coverage** exclusions rather than analysis
exclusions — every excluded file is still scanned for bugs, vulnerabilities and
smells. A record needs no test; it can still contain a mistake.

| Project | Excluded from coverage |
| --- | --- |
| Control API | `ControlApiApplication`, `dto/**`, `domain/**`, `enums/**`, `repository/projection/**` |
| Monitor Worker | `MonitorWorkerApplication`, `dto/**`, `enums/**` |
| Notification Service | `NotificationServiceApplication`, `dto/**`, `enums/**` |
| Frontend *(local coverage only)* | `src/main.tsx`, `src/types/**`, `*.d.ts` |

Two of those lists are shorter than the first on purpose. **`domain/**` is only
excluded in the Control API**, where the entities were checked and carry nothing
but Lombok accessors and `equals`/`hashCode`. The other two services keep their
entities in the measurement, because those hold real behaviour:

```text
monitor-worker       Incident.resolve()
                     OutboxEvent.markPublished() / markFailed()
notification-service NotificationDelivery.markSent() / recordFailedAttempt()
```

Excluding those would have hidden genuine state-transition logic behind a
plausible-looking rule, which is exactly the way a coverage number stops meaning
anything.

**Nothing that carries business logic is excluded** — not the Control API
services, not `ProjectAccessService` or `MonitorAccessService`, not
`DestinationPolicy`, `HttpHealthChecker`, `IncidentEventRecorder`,
`OutboxPublisher`, `NotificationEventProcessor`, `EmailDeliveryService`,
`IncidentEmailComposer`, nor the frontend's auth, permission or API-client code.

---

## Reading the results

<http://localhost:9000/projects> lists all three. For each:

- **Overview** — the current state, and the Quality Gate result
- **Issues** — bugs, vulnerabilities and code smells, filterable by severity
- **Security Hotspots** — security-sensitive code needing a human decision.
  These are *questions*, not defects; review them, and do not bulk-mark them
  safe to tidy the dashboard
- **Measures → Coverage** — per-file coverage, and which lines are uncovered
- **Measures → Duplications** — see the note below

### Duplication is expected here

The repository intentionally contains **three independent Spring Boot
applications**. Some duplication between them is a deliberate architectural
choice, not an accident:

- the incident event contract, mirrored on the producer and consumer sides
- per-service entities mapping the same tables
- configuration property holders
- three Maven wrappers

Do not extract a shared module because Sonar reports duplication. Independent
deployability was the reason for the split, and a shared production library
would undo it.

---

## Stopping and resetting

```bash
docker compose stop sonarqube      # keeps everything
docker compose --profile quality down    # removes the container, keeps volumes
```

Analysis history survives both.

### Resetting only SonarQube

⚠ **Do not reach for `docker compose down -v` for this.** That deletes the
PulseGuard MySQL and Kafka volumes as well — every user, project, monitor and
incident in your local stack. To reset only SonarQube:

```bash
docker compose --profile quality down
docker volume rm pulseguard_sonarqube_data \
                 pulseguard_sonarqube_extensions \
                 pulseguard_sonarqube_logs
docker compose --profile quality up -d sonarqube
```

That discards the analysis history and the admin password, and returns the
instance to first-run state.

---

## Troubleshooting

**SonarQube never becomes healthy.** On a first start, give it fifteen minutes
before concluding anything, and watch `docker compose logs -f sonarqube` — the
milestones are the 72 DB migrations, `Register rules`, then `Process[web] is
up`. If the log shows Elasticsearch bootstrap errors, check that
`SONAR_ES_BOOTSTRAP_CHECKS_DISABLE` is reaching the container
(`docker compose config`). If it dies without a clear error, it is most likely
memory: stop other containers and give Docker Desktop more.

**`docker compose ps` says unhealthy but the UI works.** Then the probe is
wrong, not the server — check what it is actually reporting:

```bash
docker inspect $(docker compose ps -q sonarqube) --format '{{json .State.Health}}'
```

This exact trap was hit while building this setup: the health check originally
used `wget`, which this Ubuntu-based image does not ship, so it failed every
time against a server that was serving perfectly. It uses `curl` now. Always
read the probe's own output before believing its verdict.

**`SONAR_TOKEN is not set`.** Export it in the shell you are running the script
from. Tokens are shown once at creation; generate a new one if it is lost.

**Scanner cannot reach SonarQube.** Confirm `curl http://localhost:9000/api/system/status`
answers. Note the scanners run on your **host**, so `localhost:9000` is correct
here — this is not a container talking to another container.

**A project shows 0% coverage but the tests passed.** The analysis ran without
finding a report. Check the report actually exists
(`ls backend/<service>/target/site/jacoco/jacoco.xml`, or
`frontend/coverage/lcov.info`) and that you ran coverage *before* the scan.
Running `sonar:sonar` after a plain `clean verify` produces exactly this — the
`clean` removed the previous report and nothing regenerated it.

**Coverage looks lower than expected.** Read it before adjusting anything.
There is no threshold to lower, and the honest answer is more useful than a
flattering one.

---

## Later, in CI

Stage 14 may invoke these same commands from Jenkins — `SONAR_HOST_URL` and
`SONAR_TOKEN` are environment-driven precisely so that they can be.

Whether Jenkins should *enforce* a Quality Gate is a separate decision that has
not been made. Nothing here assumes it will, and nothing here should be read as
preparation for blocking a build on coverage.
