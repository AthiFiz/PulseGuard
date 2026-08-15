# Cloud CI/CD — Jenkins on AWS

The final delivery path for PulseGuard. One `git push` and the laptop is done:
everything after that happens on an EC2 instance in AWS.

```text
developer  ──git push──▶  GitHub  ──webhook──▶  Jenkins EC2 (public subnet)
                                                     │
                                                     ├─ detect changed paths
                                                     ├─ test   ONLY those
                                                     ├─ build  ONLY those
                                                     ├─ push   ONLY those → ECR
                                                     └─ roll   ONLY those → EKS
```

```text
Instance   pulseguard-jenkins   i-021bbb5899746db16
Type       t3.medium · Amazon Linux 2023 · 30 GiB gp3
Address    54.172.12.167 (Elastic IP)
URL        http://54.172.12.167:8080
Jenkins    2.568.2 LTS · Java 21 · one executor
```

> This is a **short-lived demonstration** controller on a public IP over plain
> HTTP. It is not a production CI/CD platform, and the
> [security section](#security) is explicit about which corners are cut and why.

---

## Contents

- [Why a separate cloud Jenkins](#why-a-separate-cloud-jenkins)
- [The instance](#the-instance)
- [Network access](#network-access)
- [Working from a different network](#working-from-a-different-network)
- [IAM — no AWS keys anywhere](#iam--no-aws-keys-anywhere)
- [Kubernetes access](#kubernetes-access)
- [GitHub webhook](#github-webhook)
- [The pipeline](#the-pipeline)
- [Change detection](#change-detection)
- [Rollback](#rollback)
- [Administration without SSH](#administration-without-ssh)
- [Troubleshooting](#troubleshooting)
- [Cost](#cost)
- [Cleanup](#cleanup)
- [Security](#security)

---

## Why a separate cloud Jenkins

Task 14's Jenkins runs in Docker Compose on a laptop. It proved the CI concept —
checkout, compile, test, publish results — and it remains in the repository as
exactly that: a local development and learning artifact.

It cannot be the delivery mechanism, for one structural reason: **it only exists
when the laptop is on**. A deployment pipeline that requires a particular
machine to be awake is not a pipeline, it is a manual process with extra steps.

The cloud controller also holds the AWS identity. Deploying from a laptop means
AWS credentials on a laptop; deploying from an EC2 instance with an instance
role means no credentials exist to leak.

| | Task 14 (local) | Task 18 (cloud) |
| --- | --- | --- |
| Runs in | Docker Compose on the laptop | EC2 system service |
| Trigger | Manual "Build Now" | GitHub webhook |
| Scope | All four components, every build | Only what changed |
| Ends at | Test results | Running pods in EKS |
| AWS identity | none | EC2 instance role |

---

## The instance

Native Jenkins, **not** containerised. This controller builds Docker images, and
running it inside a container would mean either Docker-in-Docker or mounting the
host's Docker socket into a container — both add moving parts to solve a problem
that does not exist on a dedicated CI host.

Everything was installed by EC2 user data on first boot; the log is at
`/var/log/pulseguard-bootstrap.log`.

```text
Java 21   Amazon Corretto 21.0.12
Jenkins   2.568.2 LTS (systemd service, enabled)
Docker    25.0.x (systemd service, enabled)
Node      22.23.2 (official tarball — the AL2023 repo lags)
kubectl   1.36.3 (matched to the EKS control plane)
AWS CLI   2.33.x (preinstalled on AL2023)
Git       2.50.x
```

Maven is deliberately absent: each backend carries its own `./mvnw` wrapper, so
a global Maven would only introduce a version that disagrees with the project's.

The `jenkins` user is in the `docker` group. That is **host-level privilege** —
membership is equivalent to root on this box — and it is acceptable only because
this instance is dedicated to CI, short-lived, and runs nothing else.

---

## Network access

One security group, `pulseguard-jenkins-sg`, with two kinds of rule on port
8080 and **nothing on port 22**:

| Rule description | Source | Purpose |
| --- | --- | --- |
| `PulseGuard Jenkins UI` | your `/32` | Browser access |
| `GitHub webhook` × 4 | GitHub's published hook ranges | Webhook delivery |

**A common misconception worth stating plainly:** the GitHub rules have nothing
to do with where *you* are. When you `git push`, your laptop talks to GitHub;
then GitHub's servers call Jenkins. The webhook always arrives from GitHub's
addresses, so you can push from any network, anywhere, and delivery is
unaffected. Only the *browser* rule is tied to your location.

The UI is not open to `0.0.0.0/0`. A public Jenkins on HTTP is scanned
continuously, and this one holds an IAM role that can push images to ECR and
change what runs in the cluster — so the single-address restriction is doing
real work, not ceremony.

### Keeping GitHub's ranges current

GitHub changes its hook ranges occasionally and without notice. They are never
hardcoded; they are fetched from the Meta API:

```bash
./scripts/aws/sync-github-webhook-cidrs.sh --dry-run   # show the difference
./scripts/aws/sync-github-webhook-cidrs.sh             # apply it
```

The script adds missing ranges, removes stale ones, and **only** touches rules
described `GitHub webhook`. It is idempotent — a second run reports
`already in sync`.

---

## Working from a different network

The Jenkins UI is reachable from one address at a time, so moving to a demo
room, a hotspot or a different campus network breaks browser access — and only
browser access.

```bash
./scripts/aws/update-jenkins-ui-ip.sh            # use wherever you are now
./scripts/aws/update-jenkins-ui-ip.sh 1.2.3.4    # or a specific address
```

It adds the new rule before revoking the old one, so a failure halfway cannot
lock you out entirely. **GitHub webhook rules are never modified**, which means
pushes keep deploying even while you cannot open the UI.

---

## IAM — no AWS keys anywhere

```text
role              PulseGuardJenkinsRole
instance profile  PulseGuardJenkinsInstanceProfile
```

Jenkins reads credentials from the EC2 instance metadata service. There is no
`AWS_ACCESS_KEY_ID` in Jenkins credentials, in the Jenkinsfile, in an
environment variable or on disk — so there is nothing to rotate and nothing to
leak. Confirmed on the box itself:

```console
$ aws sts get-caller-identity --query Arn --output text
arn:aws:sts::423151037862:assumed-role/PulseGuardJenkinsRole/i-021bbb5899746db16
```

Two policies are attached, both narrow:

**`AmazonSSMManagedInstanceCore`** — Session Manager access, which is what
allows port 22 to stay closed.

**`PulseGuardJenkinsDeployPolicy`** — hand-written, not `ecr:*`:

- `ecr:GetAuthorizationToken` on `*` — the API has no resource dimension, so
  this cannot be scoped further
- push and read actions on **exactly the four PulseGuard repositories**, named
  by ARN. Jenkins cannot touch any other repository in the account
- `eks:DescribeCluster` on **`pulseguard-eks` only** — enough for
  `aws eks update-kubeconfig`, and nothing else. It cannot create, modify or
  delete a cluster

---

## Kubernetes access

Being allowed to call the EKS API is separate from being allowed to do anything
inside the cluster. Jenkins gets the second through an **EKS access entry**
mapping its IAM role to a Kubernetes group:

```text
PulseGuardJenkinsRole  ──access entry──▶  group: pulseguard-deployer
                                                │
                                          Role + RoleBinding
                                          namespace: pulseguard
```

The Role is in [`k8s/jenkins-deployer-rbac.yaml`](../k8s/jenkins-deployer-rbac.yaml).
It grants `get/list/watch/patch/update` on Deployments, read on ReplicaSets,
Pods, pod logs and Events — and that is all.

**It grants no access to Secrets at all.** The pipeline changes a container
image; the running Deployments already reference `pulseguard-secrets` and
`pulseguard-smtp-secrets` themselves and Kubernetes injects them at pod start.
Jenkins never needs the database password, the JWT key or the Gmail App
Password, so it cannot read them. Verified:

```console
$ kubectl auth can-i get secrets      -n pulseguard --as-group=pulseguard-deployer --as=<role>
no
$ kubectl auth can-i patch deployments -n pulseguard --as-group=pulseguard-deployer --as=<role>
yes
$ kubectl auth can-i delete deployments -n pulseguard --as-group=pulseguard-deployer --as=<role>
no
$ kubectl auth can-i get nodes                       --as-group=pulseguard-deployer --as=<role>
no
```

That boundary matters because Jenkins is the most exposed thing in this system.
A compromised Jenkins can restart pods with a different image — bad — but it
cannot walk away with every credential in the namespace.

> Enabling access entries required switching the cluster's authentication mode
> from `CONFIG_MAP` to `API_AND_CONFIG_MAP`. That is additive: existing
> `aws-auth` mappings keep working.

---

## GitHub webhook

Created by hand in the repository settings, so Jenkins needs no GitHub
permission to manage hooks:

```text
Payload URL    http://54.172.12.167:8080/github-webhook/
Content type   application/json
Events         Push events only
SSL            n/a — plain HTTP, see Security
```

The job uses **GitHub hook trigger for GITScm polling**. There is no timer and
no SCM polling: builds start because GitHub said something changed, not because
a clock fired.

> **Webhook secret.** GitHub can HMAC-sign deliveries. That is not configured
> here, so delivery authenticity rests on the security-group allowlist plus the
> authenticated Jenkins UI. Stated as a known demo limitation rather than
> pretending the stronger control is in place.

---

## The pipeline

All logic lives in the repository's [`Jenkinsfile`](../Jenkinsfile) — nothing is
typed into the Jenkins UI, so the pipeline is reviewable, diffable and restorable
with the rest of the source.

```text
Environment              tool versions, account discovery, image tag
Detect Changes           which components are affected
Test: <component>        only for changed components
ECR Login                only if something deployable changed
Build & Push: <c>        only changed images
Configure kubectl        context safety check
Deploy Changed           set image + rollout status (+ rollback on failure)
Deployed State           prints every deployment's image
```

Options: `disableConcurrentBuilds()` (two runs would race each other's
`kubectl set image`), 10 builds of history, 60-minute timeout.

The image tag is the **first 12 characters of the commit SHA**. Never `latest` —
a tag that means something different every push cannot answer "what is running?"

---

## Change detection

```groovy
backend/control-api/**          → control-api
backend/monitor-worker/**       → monitor-worker
backend/notification-service/** → notification-service
frontend/**                     → frontend
```

Deliberately simple prefix matching. A dependency graph would be more clever and
would need maintaining; path prefixes are obvious from the directory layout and
wrong in ways that are easy to see.

**Which commit is the baseline?** `GIT_PREVIOUS_SUCCESSFUL_COMMIT`, when it
exists — meaning "everything since the last build that actually deployed", so a
change is never skipped just because the build that would have shipped it
failed. Two fallbacks handle the rest:

- the recorded commit is **not in this clone** (force-push, rebase) → fall back
  to `HEAD~1`
- there is **no previous build at all** (first run) → also `HEAD~1`
- the repository has a **single commit** → treat every path as changed

`FORCE_COMPONENT` is a build parameter that processes one component regardless
of the diff. It exists so the cloud path could be proven end to end without
inventing a meaningless source change, and normal webhook builds leave it at
`none`.

**A change with no deployable component** — documentation, `k8s/**`, the
`Jenkinsfile` itself, these scripts — runs Checkout and Detect Changes, prints
`No deployable component changed`, and stops. No tests, no images, no restarts.

Deployment is **main-only**. Other branches may run their tests; nothing reaches
ECR or EKS from them.

---

## Rollback

If `kubectl rollout status` does not go healthy within its timeout, the pipeline
runs `kubectl rollout undo` for that deployment, waits for the previous revision
to come back, and then **still fails the build**.

That last part is deliberate. A green build for a deployment that did not
actually deploy is worse than a red one: it hides the problem behind a
successful-looking pipeline. The rollback protects the running system; the
failure tells you the truth.

---

## Administration without SSH

Port 22 has no inbound rule. Shell access goes through Session Manager, using
the instance role rather than a key pair:

```bash
aws ssm start-session --target i-021bbb5899746db16 --profile pulseguard

# or one-shot, without an interactive session:
aws ssm send-command --profile pulseguard \
  --instance-ids i-021bbb5899746db16 --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl status jenkins --no-pager"]'
```

No key pair exists for this instance, so there is no private key to lose.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Browser times out | Your IP changed — run `update-jenkins-ui-ip.sh` |
| GitHub shows a failed delivery | Hook ranges drifted — run `sync-github-webhook-cidrs.sh` |
| Build not triggered by push | Job trigger not set to *GitHub hook trigger for GITScm polling*, or the push was not to `main` |
| `docker: permission denied` | `jenkins` user not in the `docker` group, or Jenkins was not restarted after adding it |
| `no basic auth credentials` on push | ECR Login stage skipped or failed; check the instance role |
| `Error from server (Forbidden)` | RBAC — confirm the access entry and RoleBinding exist |
| `kubectl` context abort | `aws eks update-kubeconfig` did not run, or points at another cluster |
| Everything rebuilt unexpectedly | No usable baseline commit; check the printed diff range |

Build logs: Jenkins UI → **PulseGuard-CD** → build → **Console Output**.
Service logs: `journalctl -u jenkins -n 100 --no-pager` over SSM.

---

## Cost

```text
t3.medium on-demand   ~$0.0416/hr   ≈ $1.00/day
30 GiB gp3            ~$0.08/GB-mo  ≈ $0.08/day
Elastic IP (in use)   ~$0.005/hr    ≈ $0.12/day
```

**≈ $1.20/day**, taking the whole PulseGuard AWS environment to roughly
**$9/day** alongside EKS, the node, NAT, ALB, RDS and MSK.

---

## Cleanup

Jenkins-specific teardown, in order:

```bash
# 1. stop GitHub delivering to an address that will not exist
#    (GitHub → repo → Settings → Webhooks → delete)

# 2. terminate the instance
aws ec2 terminate-instances --instance-ids i-021bbb5899746db16 --profile pulseguard
aws ec2 wait instance-terminated --instance-ids i-021bbb5899746db16 --profile pulseguard

# 3. release the Elastic IP — an unassociated address still bills
aws ec2 release-address --allocation-id <jenkins-eip-alloc> --profile pulseguard

# 4. security group (only after the ENI is gone)
aws ec2 delete-security-group --group-id sg-0199c1ebe29d141b3 --profile pulseguard

# 5. IAM
aws iam remove-role-from-instance-profile --instance-profile-name PulseGuardJenkinsInstanceProfile \
    --role-name PulseGuardJenkinsRole --profile pulseguard
aws iam delete-instance-profile --instance-profile-name PulseGuardJenkinsInstanceProfile --profile pulseguard
aws iam detach-role-policy --role-name PulseGuardJenkinsRole \
    --policy-arn arn:aws:iam::423151037862:policy/PulseGuardJenkinsDeployPolicy --profile pulseguard
aws iam detach-role-policy --role-name PulseGuardJenkinsRole \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore --profile pulseguard
aws iam delete-policy --policy-arn arn:aws:iam::423151037862:policy/PulseGuardJenkinsDeployPolicy --profile pulseguard
aws iam delete-role --role-name PulseGuardJenkinsRole --profile pulseguard

# 6. cluster access
aws eks delete-access-entry --cluster-name pulseguard-eks \
    --principal-arn arn:aws:iam::423151037862:role/PulseGuardJenkinsRole --profile pulseguard
kubectl delete -f k8s/jenkins-deployer-rbac.yaml
```

The rest of the environment — ALB, EKS, MSK, NAT, RDS, ECR — tears down as
documented in [`aws.md`](aws.md) and [`kubernetes.md`](kubernetes.md).

---

## Security

What is in place:

- Jenkins UI restricted to a single `/32`, never `0.0.0.0/0`
- Webhook ingress restricted to GitHub's published ranges, kept current by script
- **No inbound SSH** — administration through Session Manager
- **No static AWS credentials** — EC2 instance role only
- ECR permissions scoped to four named repositories
- EKS permissions scoped to one namespace, no Secret access, no delete
- Pipeline defined in source control, not in the Jenkins UI
- Anonymous access disabled; a unique demo password on the admin account

What is knowingly cut, because this is a temporary demonstration:

- **HTTP, not HTTPS.** No TLS, no domain, no certificate. The Jenkins login
  password crosses the network in cleartext. The `/32` restriction is what makes
  this tolerable, and only briefly.
- **No webhook HMAC secret.** Delivery authenticity rests on the source-IP
  allowlist.
- **`jenkins` is in the `docker` group**, which is host-root-equivalent on this
  instance.
- **Controller runs its own builds.** One executor, no agents, no isolation
  between the pipeline and the controller.
- **No HA.** One instance; if it dies, deployment stops until it is rebuilt.

None of these are recommendations. **Destroy this instance after the
demonstration** rather than leaving a public HTTP Jenkins with cluster access
running indefinitely.
