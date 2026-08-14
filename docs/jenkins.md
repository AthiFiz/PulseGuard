# PulseGuard — Jenkins CI Runbook

One repeatable pipeline that answers a single question: **does the current
source build and pass its automated checks?**

```bash
docker compose --profile ci up -d --build jenkins
open http://localhost:8085
```

---

## Contents

- [What this is, and what it is not](#what-this-is-and-what-it-is-not)
- [Starting Jenkins](#starting-jenkins)
- [First-run setup](#first-run-setup)
- [Creating the PulseGuard-CI job](#creating-the-pulseguard-ci-job)
- [The pipeline](#the-pipeline)
- [Running normal CI](#running-normal-ci)
- [Running Sonar-enabled CI](#running-sonar-enabled-ci)
- [Reading the results](#reading-the-results)
- [When it fails](#when-it-fails)
- [Persistence and resetting](#persistence-and-resetting)
- [Resource usage](#resource-usage)
- [Security posture](#security-posture)
- [Troubleshooting](#troubleshooting)
- [Stopping Jenkins](#stopping-jenkins)

---

## What this is, and what it is not

**Continuous integration.** The pipeline checks out the repository, runs the
three backend test suites, runs the frontend tests, typecheck and production
build, and optionally uploads SonarQube analysis. Then it stops.

**Not continuous deployment.** There is no Docker build stage, no image push,
no registry, no deployment step and no infrastructure call anywhere in the
Jenkinsfile. Stage 12 already proved the images build; publishing and deploying
them belong to later stages, and until then Jenkins has no need for the
capability — so it does not have it.

Jenkins is development tooling, like SonarQube and Kafka UI:

- no PulseGuard service declares `depends_on: jenkins`
- `docker compose up -d` does not start it
- stopping Jenkins has no effect on the running application
- developers never have to use it — `./mvnw clean verify` and the npm scripts
  work on the host exactly as before

### It needs no infrastructure

The suites Jenkins runs are the fast ones: no MySQL, no Kafka, no SMTP, no
Docker. That is the Stage 11 decision and it is what makes this pipeline simple
— nothing has to be started before a build, and a green result is never a
coincidence of what happened to be running.

The flip side is what a green build does **not** tell you: the schema, the
Kafka round trip and email delivery are still verified by hand
(see [docs/docker.md](docker.md)).

---

## Starting Jenkins

```bash
docker compose --profile ci up -d --build jenkins
```

Naming the service explicitly matters. `docker compose --profile ci up -d` on
its own would also start the whole PulseGuard runtime stack, because the core
services have no profile and are therefore always eligible. The pipeline does
not need them.

| | |
| --- | --- |
| UI | <http://localhost:8085> |
| Port | `127.0.0.1:8085` → container `8080` |
| Image | built from `ci/jenkins/`, based on `jenkins/jenkins:2.568.2-lts-jdk21` |
| Volume | `jenkins_home` |

Port 8085 rather than 8080 because the Control API already owns 8080 on the
host. The agent port 50000 is deliberately not published — this instance builds
on its own controller and has no external agents.

Watch it come up:

```bash
docker compose ps                    # look for (healthy)
docker compose logs -f jenkins
```

---

## First-run setup

The setup wizard is left enabled on purpose, so the admin user is created
interactively and no credentials are ever written into this repository.

**1. Unlock.** Jenkins prints an initial password to the log and stores it in
the container:

```bash
docker compose exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

**2. Plugins.** Choose **"Select plugins to install" → none**. The image
already contains everything the pipeline needs, and the "suggested" bundle
installs a few dozen plugins this project has no use for.

**3. Admin user.** Create your own. Do not commit it anywhere.

**4. Set one executor.** Manage Jenkins → Nodes → Built-In Node → Configure →
**# of executors: 1**.

That last step is worth doing. With more than one executor, two pipeline runs
can overlap and put six Maven builds on the machine at once — which is exactly
the CPU saturation that has made tests flaky in earlier stages. The Jenkinsfile
also sets `disableConcurrentBuilds()`, so this is belt and braces.

---

## Creating the PulseGuard-CI job

**New Item → `PulseGuard-CI` → Pipeline → OK.**

Under **Pipeline**:

| Field | Value |
| --- | --- |
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | `/pulseguard-repo` |
| Branch Specifier | the branch you want to build, e.g. `*/main` |
| Script Path | `Jenkinsfile` |

`/pulseguard-repo` is the repository, bind-mounted read-only into the container.
Jenkins clones from it into its own workspace and never writes back, so builds
cannot disturb your working tree.

**This means Jenkins builds commits, not your working tree.** Uncommitted
changes are invisible to it — which is correct CI behaviour, and worth knowing
before wondering why an edit had no effect.

To build from GitHub instead, use the repository URL and add a Jenkins
credential if it is private (**Manage Jenkins → Credentials**). Do not put a
username, token or SSH key into the Jenkinsfile.

Everything else — the stages, the commands, the parameters — comes from the
committed `Jenkinsfile`. The job configuration deliberately holds nothing but
*where to find the code*.

---

## The pipeline

```text
Environment            java / node / npm / git versions
      ↓
Control API            ./mvnw clean verify        → JUnit results
      ↓
Monitor Worker         ./mvnw clean verify        → JUnit results
      ↓
Notification Service   ./mvnw clean verify        → JUnit results
      ↓
Frontend               npm ci
                       npx vitest run
                       npm run typecheck
                       npm run build
      ↓
SonarQube              skipped unless RUN_SONAR = true
      ↓
SUCCESS / FAILURE
```

**The three backends run sequentially, not in parallel.** They are independent
Maven projects and could run concurrently, but three JVMs compiling and testing
at once saturates this machine, and starved tests fail for reasons unrelated to
the code. Correctness before speed; a bigger CI machine can revisit it.

Each backend uses its **own** `./mvnw`. There is no shared Maven parent and
Jenkins does not supply a global Maven installation — the wrapper each service
already ships is what builds it.

Pipeline options, and why:

| Option | Value | Reason |
| --- | --- | --- |
| `disableConcurrentBuilds()` | — | Two runs would compete for a small machine |
| `buildDiscarder` | 10 builds | A demo instance does not need unbounded history |
| `timeout` | 60 min | Backend suites are slow here; a tight timeout would fail builds for being slow rather than wrong |

---

## Running normal CI

**Build with Parameters → RUN_SONAR unchecked → Build.**

Nothing needs to be running first — not MySQL, not Kafka, not Mailpit, not
SonarQube. The SonarQube stage shows as skipped.

---

## Running Sonar-enabled CI

Only when you actually want a scan, because SonarQube is the heaviest container
in the project.

**1. Start SonarQube alongside Jenkins:**

```bash
docker compose --profile ci --profile quality up -d jenkins sonarqube
```

**2. Add the token as a Jenkins credential.** Generate an analysis token in
SonarQube (My Account → Security), then in Jenkins:

```text
Manage Jenkins → Credentials → System → Global → Add Credentials
Kind: Secret text
Secret: <the token>
ID: sonar-token
```

The ID must be exactly `sonar-token` — that is what the Jenkinsfile binds. The
token is never written to the Jenkinsfile, `compose.yaml`, any script or any
documentation.

**3. Build with `RUN_SONAR` checked.**

The stage reuses `scripts/sonar-local.sh` rather than restating the scan
commands, so there is one definition of how PulseGuard is analysed. It covers
the three backends; **the frontend stays excluded**, because SonarQube's
JavaScript analyzer rejects this project's Node version and the toolchain was
not going to move for a reporting tool. Jenkins having Node 22 does not reopen
that — the analyzer runs against the project's Node, not Jenkins'.

### SonarQube is reached by service name

```text
from your browser      http://localhost:9000
from inside Jenkins    http://sonarqube:9000
```

Inside the Jenkins container `localhost` is Jenkins. The Jenkinsfile sets
`SONAR_HOST_URL=http://sonarqube:9000` for this reason.

### The Quality Gate is not a build condition

There is no `waitForQualityGate` and no `-Dsonar.qualitygate.wait=true`. The
scan uploads its analysis and the build moves on. Sonar findings stay
informational, exactly as decided in Stage 13.

The stage *can* still fail — if SonarQube is unreachable, the token is wrong,
or the scanner itself errors. That is a broken scan, which is worth failing on.
A finding on the dashboard is not.

---

## Reading the results

**Test results.** Each backend stage publishes its Maven Surefire XML through
the `junit` step, so Jenkins shows counts, failures and a trend across builds
rather than only console output. `allowEmptyResults: true` keeps a build that
died before producing reports from failing twice for the same reason.

Frontend tests are reported through console output and exit status only. Adding
a JUnit reporter purely so Jenkins could draw the same table was not worth a
new dependency.

---

## When it fails

A failing stage stops the pipeline — later stages do not run and cannot report
success. A broken Control API test means the Monitor Worker stage never starts,
and the build is red.

This is the intended behaviour and is worth checking once yourself: break a
test locally, commit it on a scratch branch, build that branch, and watch the
pipeline go red at the right stage.

---

## Persistence and resetting

`jenkins_home` holds the entire instance: the admin user, the job, credentials
and build history. It survives `docker compose down`.

> ⚠ **Do not use `docker compose down -v` to reset Jenkins.** That also deletes
> PulseGuard's MySQL and Kafka volumes and SonarQube's analysis history.

To reset only Jenkins:

```bash
docker compose --profile ci down
docker volume rm pulseguard_jenkins_home
docker compose --profile ci up -d jenkins
```

That returns it to first-run state — new initial password, no job, no
credentials.

---

## Resource usage

A **development baseline only**, not sizing guidance. Jenkins itself is modest;
the pipeline is expensive because it runs six real builds.

Measure it yourself with:

```bash
docker stats --no-stream
```

Do not run Jenkins, SonarQube and Kafka UI at once on a constrained laptop. For
a Sonar-enabled build, Jenkins and SonarQube together is already a lot.

---

## Security posture

- **Bound to `127.0.0.1`** — not reachable from the rest of your network.
- **No Docker socket mounted**, and no `privileged`. The pipeline builds no
  images, so the container cannot reach the Docker daemon at all.
- **No AWS credentials**, no cloud CLI, no `kubectl`.
- **Secrets live in Jenkins Credentials.** The Sonar token is bound only inside
  the Sonar stage, and the `sh` step is single-quoted so Groovy never
  interpolates it into the build log.
- **Nothing committed.** No admin password, no token, no repository credential
  appears in any tracked file.
- **The repository is mounted read-only**, so a build cannot modify your
  working tree.

Honest limitations of that list:

- The controller executes builds directly on itself. A production Jenkins
  separates the controller from its agents; that separation is not worth the
  machinery for a single-developer local instance, and it is a real difference.
- The setup wizard, admin user and job are configured by hand. No Configuration
  as Code, deliberately — it is useful for a real deployment and overhead here.

---

## Troubleshooting

**Jenkins never becomes healthy.** Give it a few minutes and read
`docker compose logs -f jenkins`. The health check calls `/login`, which only
answers once the UI is genuinely up.

**"detected dubious ownership" from Git.** The image sets
`safe.directory '*'` for exactly this — the bind-mounted repository carries the
host user's ownership while Jenkins runs as uid 1000. If it reappears, confirm
the setting survived the image rebuild.

**The pipeline does not see a change you just made.** Jenkins builds commits.
Commit it, then rebuild.

**`npm ci` fails complaining about the lockfile.** `package.json` and
`package-lock.json` have drifted. Fix it locally with `npm install` and commit
the lockfile — do not switch CI to `npm install`, which would hide the drift.

**The Sonar stage cannot reach SonarQube.** Check it is actually running
(`docker compose ps`) and that the Jenkinsfile is using `http://sonarqube:9000`
rather than `localhost`.

**A build is queued and never starts.** Something else is running:
`disableConcurrentBuilds()` allows one at a time.

---

## Stopping Jenkins

```bash
docker compose stop jenkins            # keeps everything
docker compose --profile ci down       # removes the container, keeps jenkins_home
```

Both preserve the job, credentials and history.
