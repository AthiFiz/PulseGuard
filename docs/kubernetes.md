# PulseGuard on Kubernetes (Amazon EKS)

How PulseGuard runs on EKS, why the network is shaped the way it is, and what it
costs. The manifests themselves are in [`../k8s/`](../k8s/); AWS-side
construction is in [`aws.md`](aws.md).

```text
Cluster    pulseguard-eks        Kubernetes 1.36, platform eks.10
Region     us-east-1
VPC        vpc-0d216ba975638bc5b (reused from Stage 15A)
Namespace  pulseguard
```

> **This is a short-lived demonstration environment.** It costs roughly
> **$5–6/day** and is intended to live about three days. The teardown order in
> [Cleanup](#cleanup) matters — some resources are created by controllers rather
> than by hand and will keep billing if orphaned.

---

## Contents

- [Architecture](#architecture)
- [Why the nodes are private](#why-the-nodes-are-private)
- [The cluster](#the-cluster)
- [The AWS Load Balancer Controller](#the-aws-load-balancer-controller)
- [The database](#the-database)
- [Configuration and secrets](#configuration-and-secrets)
- [The workloads](#the-workloads)
- [Probes, and two things that broke](#probes-and-two-things-that-broke)
- [What was verified](#what-was-verified)
- [The Kafka gap](#the-kafka-gap)
- [Security posture](#security-posture)
- [Cost](#cost)
- [Cleanup](#cleanup)
- [Known limitations](#known-limitations)

---

## Architecture

```text
                            Internet
                                │
                                │  HTTP :80, restricted to one /32
                                ▼
                ┌───────────────────────────────┐
                │  Application Load Balancer     │  PUBLIC subnets
                │  internet-facing · 2 AZs       │  10.0.0.0/20 · 10.0.16.0/20
                └───────────────┬───────────────┘
                                │  target-type: ip → pod IPs directly
                                ▼
        ┌───────────────────────────────────────────────┐
        │            EKS pods (one t3.medium)            │  PRIVATE subnets
        │                                                │  10.0.128.0/20
        │   ┌──────────┐   /api   ┌─────────────────┐    │  10.0.144.0/20
        │   │ frontend │ ───────▶ │   control-api   │    │
        │   │  nginx   │          │   ClusterIP     │    │
        │   └──────────┘          └────────┬────────┘    │
        │   ┌────────────────┐             │             │
        │   │ monitor-worker │─────────────┤             │
        │   │  replicas: 1   │             │             │
        │   └────────────────┘             │             │
        │   ┌──────────────────────┐       │             │
        │   │ notification-service │       │             │
        │   │     replicas: 0      │       │             │
        │   └──────────────────────┘       │             │
        └──────────────────────────────────┼─────────────┘
                                           │ 3306
                                           ▼
                              ┌─────────────────────────┐
                              │   Amazon RDS MySQL      │  PRIVATE subnets
                              │   8.0.46 · no public IP │  SG source: EKS SG
                              └─────────────────────────┘

  Outbound only — ECR pulls, EKS registration, monitored endpoints:

     private nodes ──▶ NAT Gateway ──▶ Internet Gateway ──▶ Internet
                       (public subnet A · ONE only)
```

The shape has one rule behind it: **exactly one thing is reachable from the
internet, and it is not application code.** The ALB terminates public traffic;
everything that executes PulseGuard sits in private subnets with no public
address.

---

## Why the nodes are private

A managed node group placed in public subnets would work, cost less, and be
simpler — no NAT Gateway at ~$32/month. It was rejected because a public node is
an internet-reachable host running your containers, protected only by security
group rules that are easy to loosen by accident.

Private nodes still need outbound access — to register with the EKS control
plane and to pull images from ECR — which is what the NAT Gateway provides.
Inbound traffic never uses it.

### One NAT Gateway, and the trade that makes

```text
pulseguard-nat   nat-00046ef0fb9fab41c   public subnet A (us-east-1a)
                 Elastic IP  100.60.173.72

rtb-private1 (us-east-1a)   0.0.0.0/0 → NAT
rtb-private2 (us-east-1b)   0.0.0.0/0 → NAT   ← crosses an AZ boundary
```

A production system would run one NAT per Availability Zone. This one is
deliberately a single point of failure:

- if `us-east-1a` fails, **all** private egress stops, in both AZs
- traffic from private subnet B crosses an AZ to reach it, adding latency and
  cross-AZ data transfer charges

A second gateway doubles the largest line on this bill to buy redundancy that a
three-day demonstration cannot use. It is the right call here and the wrong call
in production, which is the sort of distinction worth stating rather than
leaving implied.

---

## The cluster

```bash
aws eks create-cluster --name pulseguard-eks --kubernetes-version 1.36 \
  --role-arn arn:aws:iam::423151037862:role/pulseguard-eks-cluster-role \
  --resources-vpc-config "subnetIds=<priv-a>,<priv-b>,<pub-a>,<pub-b>,\
endpointPublicAccess=true,endpointPrivateAccess=true,publicAccessCidrs=<your-ip>/32"
```

**The Kubernetes API is not open to the internet.** Public access is enabled but
restricted to a single `/32`, so `kubectl` works from the developer machine and
from nowhere else. Private access is also on, so in-cluster traffic never leaves
the VPC.

All four subnets are attached to the cluster. The private pair is where nodes
and pods live; the public pair is there so the load balancer controller has
somewhere to put an internet-facing ALB.

### Node group

```text
pulseguard-nodegroup
  instance     t3.medium · On-Demand
  scaling      min 1 · desired 1 · max 2
  AMI          AL2023_x86_64_STANDARD
  disk         20 GiB gp3
  subnets      private A + private B
```

```console
$ kubectl get nodes -o wide
NAME                           STATUS   VERSION               INTERNAL-IP    EXTERNAL-IP
ip-10-0-151-138.ec2.internal   Ready    v1.36.2-eks-254016e   10.0.151.138   <none>
```

`EXTERNAL-IP  <none>` is the acceptance criterion. The node reaches the internet
outbound through NAT and cannot be reached inbound at all.

### IAM, kept narrow

| Role | Policies |
| --- | --- |
| `pulseguard-eks-cluster-role` | `AmazonEKSClusterPolicy` |
| `pulseguard-eks-node-role` | `AmazonEKSWorkerNodePolicy`, `AmazonEKS_CNI_Policy`, `AmazonEC2ContainerRegistryPullOnly` |

`AmazonEC2ContainerRegistryPullOnly` rather than the more commonly pasted
`ReadOnly`: nodes need to pull images, not to enumerate the registry. No
`AdministratorAccess` anywhere.

---

## The AWS Load Balancer Controller

The controller turns a Kubernetes `Ingress` into a real ALB. It needs AWS
permissions, and the way it gets them matters more than the install itself.

### IRSA, not access keys

```text
EKS OIDC provider  oidc.eks.us-east-1.amazonaws.com/id/C9D6555C5F257026A643B48D9F7DCFFF
IAM role           PulseGuardAWSLoadBalancerControllerRole
IAM policy         PulseGuardAWSLoadBalancerControllerIAMPolicy  (official, v2.14.1)
ServiceAccount     kube-system/aws-load-balancer-controller
```

The role's trust policy names exactly one service account:

```json
"StringEquals": {
  "…:aud": "sts.amazonaws.com",
  "…:sub": "system:serviceaccount:kube-system:aws-load-balancer-controller"
}
```

No AWS access key exists in any pod, Secret or environment variable. The pod
presents a projected service-account token, STS exchanges it for temporary
credentials, and nothing long-lived is ever stored.

The policy is AWS's published one, downloaded from the controller's own repo at
the matching tag — 16 scoped statements, **zero** wildcard actions. Replacing it
with `Action: "*"` to make an error go away would defeat the point of using IRSA
at all.

### Install

```bash
helm repo add eks https://aws.github.io/eks-charts && helm repo update eks

kubectl apply -f -   # ServiceAccount annotated with the role ARN
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system --version 1.14.1 \
  --set clusterName=pulseguard-eks \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set region=us-east-1 --set vpcId=vpc-0d216ba975638bc5b
```

> **Version note.** Chart **1.14.1** ships controller **v2.14.1**. Chart 1.14.0
> ships v2.14.0, so the two numbers do not pair the way they are often quoted
> together. The chart repository has since moved to a 3.x line (chart 3.5.0 /
> controller v3.5.0); v2.14.1 was kept here because the Ingress annotation
> syntax used below is the v2 syntax and a short-lived environment is a poor
> place to absorb a major-version change.

Helm is used **only** for this controller. PulseGuard itself is plain YAML.

---

## The database

RDS was moved from its temporary Stage 15B configuration to a private one before
any application pod was deployed.

| | Before (Stage 15B) | After (Stage 16) |
| --- | --- | --- |
| Subnet group | public A/B | **private A/B** |
| `PubliclyAccessible` | `true` | **`false`** |
| Port 3306 source | laptop `/32` | **EKS cluster SG** `sg-0e154cd8cf8abedc4` |

MySQL stays at **8.0.46**, matching the Compose container exactly.

**AWS will not move a running instance between subnet groups inside one VPC** —
`ModifyDBInstance` rejects it, and removing the public subnets from the old
group fails because the instance's network interface is still in one. The
instance held no application data, only the Flyway schema, so it was deleted and
recreated directly into the private group.

### Inspecting the database now that it is private

Direct connections from a laptop no longer work, and that is the intended
outcome — do not re-add a `/32` rule. Tunnel through the cluster instead:

```bash
kubectl run mysql-tunnel -n pulseguard --image=alpine/socat --restart=Never -- \
  tcp-listen:3306,fork,reuseaddr \
  tcp-connect:pulseguard-mysql.cc5sk4k4err6.us-east-1.rds.amazonaws.com:3306

kubectl port-forward -n pulseguard pod/mysql-tunnel 13306:3306
```

Any MySQL client then connects to `127.0.0.1:13306`. The traffic rides the
Kubernetes API connection, which is TLS and already restricted to one address,
and the tunnel exists only while the command runs.

```bash
kubectl delete pod mysql-tunnel -n pulseguard    # when finished
```

---

## Configuration and secrets

**ConfigMap `pulseguard-config`** holds non-secret values: the RDS JDBC URL, the
frontend origin, and the SSRF policy flag. A hostname is not a credential and
the database is unreachable without one.

**Secret `pulseguard-secrets`** holds `DB_USERNAME`, `DB_PASSWORD` and
`JWT_SECRET`. It is created at deploy time, never committed:

```bash
SECRET_ARN=$(aws rds describe-db-instances --db-instance-identifier pulseguard-mysql \
  --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)
# username/password read from Secrets Manager, piped straight into kubectl
kubectl create secret generic pulseguard-secrets -n pulseguard \
  --from-literal=DB_USERNAME="$DB_USER" \
  --from-literal=DB_PASSWORD="$DB_PASS" \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)"
```

`k8s/secret.example.yaml` documents the keys with placeholder values and is
deliberately **not** gitignored; `k8s/secret.yaml` and `.env.eks` are.

This still routes the password through a laptop. External Secrets Operator or
the Secrets Store CSI driver would remove that step by letting pods read
Secrets Manager directly — the right answer for anything long-lived, and
deliberately out of scope here.

### The one setting that must differ from Docker

```yaml
MONITOR_ALLOW_PRIVATE_ADDRESSES: "false"
```

Compose sets this `true` because every address on a Compose network is private,
so the SSRF policy would otherwise refuse to monitor anything locally. In a real
VPC the same setting would let a monitor URL reach RDS, the Kubernetes API and
every pod. The application already defaults to `false`; Compose is the exception.

Verified working — a monitor pointed at a pod IP inside the VPC:

```json
{ "outcome": "FAILURE", "errorType": "BLOCKED_ADDRESS",
  "errorMessage": "Destination blocked by monitoring security policy: private address" }
```

---

## The workloads

| Deployment | Replicas | Service | Why |
| --- | --- | --- | --- |
| `control-api` | 1 | ClusterIP :8080 | Owns Flyway; never public |
| `monitor-worker` | **1** | none | No distributed locking yet |
| `notification-service` | **0** | none | No Kafka and no SMTP in AWS |
| `frontend` | 1 | ClusterIP :80 | Behind the ALB Ingress |

**`monitor-worker` must stay at 1.** The scheduler claims due monitors without
row-level locking, so a second replica duplicates every HTTP check, writes two
`monitor_checks` rows and advances consecutive-failure counters at twice the
intended rate — firing incidents early. `SELECT … FOR UPDATE SKIP LOCKED` is the
planned fix and belongs to a later stage.

### Images are pinned to git SHAs

```text
control-api           :4970e68
monitor-worker        :4970e68
notification-service  :4970e68
frontend              :4970e68-patched   ← note the suffix
```

The frontend tag matters. ECR scan-on-push found **1 CRITICAL and 15 HIGH** CVEs
in the original `4970e68` frontend image, all in OS packages of a stale
`nginx:1.29-alpine` base. `docker build --pull` did **not** fix it — the base tag
itself had not been rebuilt — so the Dockerfile gained `RUN apk upgrade
--no-cache`, and the rebuilt image scanned clean at zero findings. The vulnerable
image still exists in ECR under the plain `4970e68` tag and must never be
deployed.

### The frontend's nginx config is replaced

The image's own config contains `resolver 127.0.0.11` — Docker's embedded DNS,
which does not exist in a pod. Left alone, every `/api/` request returns 502.

`k8s/frontend-nginx-configmap.yaml` mounts a replacement over
`/etc/nginx/conf.d/default.conf`. It also drops the variable-based `proxy_pass`
the Docker config uses: that exists to force per-request re-resolution because
Compose may give a replaced container a new IP, whereas in Kubernetes
`control-api` is a Service ClusterIP that outlives any individual pod. A literal
`proxy_pass` resolved once at startup is both simpler and correct here.

This is deployment configuration, not an application change — the image is
untouched.

---

## Probes, and two things that broke

Both failures below were real, diagnosed from cluster output, and are recorded
because the error messages are misleading.

### `CreateContainerConfigError` — non-numeric user

```text
container has runAsNonRoot and image has non-numeric user (pulseguard),
cannot verify user is non-root
```

The Dockerfiles end with `USER pulseguard` — a *name*. The kubelet cannot prove
a name is non-root without resolving it, so `runAsNonRoot: true` alone fails the
container outright. The fix is to restate the UID numerically:

```yaml
runAsNonRoot: true
runAsUser: 1001
runAsGroup: 1001
```

### Startup probe 401 — Spring Security

```text
Startup probe failed: HTTP probe failed with statuscode: 401
```

The pod was healthy; the *probe* was unauthorised. `SecurityConfig.java` permits
exactly `/actuator/health` and nothing beneath it, so `/actuator/health/readiness`
and `/actuator/health/liveness` answer 401 and the kubelet reads that as
unhealthy. All probes therefore use the aggregate `/actuator/health`.

That has a consequence worth naming: the aggregate endpoint reports DOWN when the
database is unreachable, so a naive liveness probe would restart pods during an
RDS outage — a crash loop on top of an outage. The liveness probe is deliberately
slack (`failureThreshold: 6`, `periodSeconds: 20` ≈ two minutes) to distinguish a
wedged JVM from a transient database problem.

Reaching the proper liveness/readiness groups would mean editing Spring Security
and rebuilding the image. Worth doing eventually; out of scope here.

### Startup timing

Control API needs a `startupProbe` because Spring Boot plus Flyway is slow to
start. On EKS it settled at **15 seconds** — far quicker than the 181 s measured
running the same image on the development laptop. The probe allows up to 5
minutes anyway; the cost of being generous is nil, and the cost of being tight is
a restart loop that looks like a crash.

---

## What was verified

Every line below was observed, not assumed.

**Private database path** — `control-api` pod → private RDS:

```text
Database: jdbc:mysql://pulseguard-mysql.….rds.amazonaws.com:3306/pulseguard
Successfully validated 8 migrations
Current version of schema `pulseguard`: 8
Schema `pulseguard` is up to date. No migration necessary.
Started ControlApiApplication in 15.005 seconds
{"groups":["liveness","readiness"],"status":"UP"}
```

Startup is the proof, not the log line: Hibernate runs `ddl-auto=validate`, so
the application refuses to start unless every entity matches every table.

**Public path** — through the ALB:

```text
GET /                        200      static SPA
GET /monitors/1              200      SPA fallback
GET /api/v1/system/info      200      nginx → control-api → RDS
POST /api/v1/auth/register   201
POST /api/v1/auth/login      200      JWT issued
POST /api/v1/projects        201
POST /…/monitors             201
```

**Monitoring** — worker → NAT → public internet:

```json
{ "outcome": "SUCCESS", "httpStatusCode": 200, "responseTimeMs": 395 }
```

**Incident lifecycle** — a monitor deliberately pointed at a 404:

```text
consecutiveFailures: 8 · currentStatus: DOWN
incident id 1 · status OPEN · monitorId 3
```

**Kubernetes behaviour**

| Test | Result |
| --- | --- |
| Delete a Control API pod | Replacement scheduled and Ready |
| Data after pod replacement | Project and monitors intact — state is in RDS |
| `kubectl rollout restart` | Completed, old replica terminated cleanly |
| Scale frontend 1 → 2 → 1 | Both Ready; ALB returned 200 throughout |
| Worker replica count | Never left 1 |

---

## The Kafka gap

**No Kafka exists in AWS.** MSK was not created, no broker runs in the cluster,
and the laptop's broker is not exposed to the internet.

The worker's producer therefore cannot connect, and the logs say so plainly:

```text
Connection to node -1 (localhost/127.0.0.1:9092) could not be established.
Bootstrap broker localhost:9092 disconnected
```

This is not silently ignored and it is not fatal. The worker keeps monitoring
throughout — 0 restarts, checks still recorded every 60 s — because publication
failure is designed to be non-fatal and the transactional outbox simply
accumulates unpublished rows.

**The incident → Kafka → notification flow was NOT demonstrated on AWS.** It
works end to end on Docker Compose, which remains the complete environment for
that part of the system. `notification-service` runs at zero replicas here for
exactly this reason.

One observation worth recording: with no broker, the producer re-bootstraps
roughly every 50 ms, which is noisy in the logs and burns a little CPU. Harmless
over three days; it would want a backoff before anything longer-lived.

---

## Security posture

| Control | State |
| --- | --- |
| Node public IPv4 | none — private subnets only |
| Outbound internet | NAT Gateway only |
| RDS | `PubliclyAccessible=false`, private subnets |
| RDS ingress | port 3306 from the EKS cluster SG only; no CIDR rules |
| Laptop database rule | **removed** |
| Public application entry | one ALB, HTTP :80, restricted to one `/32` |
| Kubernetes API | public endpoint restricted to one `/32` |
| AWS credentials in pods | none — IRSA only |
| Committed secrets | none; `secret.example.yaml` holds placeholders |
| SSRF policy | `MONITOR_ALLOW_PRIVATE_ADDRESSES=false`, verified blocking |
| Kafka | not exposed publicly anywhere |
| Containers | non-root (UID 1001) for JVM services, `allowPrivilegeEscalation: false`, all capabilities dropped |

**HTTP, not HTTPS.** No ACM certificate, no domain, no Route 53. Traffic between
browser and ALB is unencrypted, which is acceptable only because access is
limited to one address for a few days. Production would require TLS before
anything else on this list.

The frontend pod is the one container not running as non-root: stock nginx needs
root to bind port 80 and drops its workers to an unprivileged user itself. Using
`nginx-unprivileged` on a high port would close that gap and requires an image
change.

---

## Cost

Roughly **$5–6/day**, dominated by three fixed hourly charges that bill whether
or not anyone uses the system.

| Resource | Rate (us-east-1) | ~3 days |
| --- | --- | --- |
| EKS control plane | $0.10/hr | **$7.20** |
| `t3.medium` node | $0.0416/hr | $3.00 |
| NAT Gateway | $0.045/hr + $0.045/GB | $3.24 + data |
| Application Load Balancer | ~$0.0225/hr + LCU | ~$1.70 |
| Public IPv4 × 3 (1 NAT + 2 ALB) | $0.005/hr each | $1.08 |
| EBS 20 GiB gp3 | $0.08/GB-month | $0.16 |
| RDS `db.t3.micro` + 20 GiB | $0.017/hr | $0 on free tier, else ~$1.45 |
| Secrets Manager | $0.40/month | $0.04 |
| ECR storage | ~$0.10/GB-month | ~$0.02 |

**Estimated three-day total: roughly $16–18.** That is an estimate from
published rates and observed resource types, not a bill — AWS is the only
authority on the final number.

The EKS control plane alone is ~$73/month. If this environment is left running,
it will cost far more than the rest of the project combined.

---

## Cleanup

**Order matters.** Two of these resources are created by controllers, and one
was created by hand; deleting the cluster first can orphan things that keep
billing.

```bash
# 1. Ingress FIRST — this is what deletes the ALB
kubectl delete ingress frontend -n pulseguard

# 2. verify before continuing (expect an empty list)
aws elbv2 describe-load-balancers --profile pulseguard \
  --query "LoadBalancers[?VpcId=='vpc-0d216ba975638bc5b'].LoadBalancerName"

# 3. the rest of the application
kubectl delete namespace pulseguard

# 4. node group, then cluster
aws eks delete-nodegroup --cluster-name pulseguard-eks \
  --nodegroup-name pulseguard-nodegroup --profile pulseguard
aws eks wait nodegroup-deleted --cluster-name pulseguard-eks \
  --nodegroup-name pulseguard-nodegroup --profile pulseguard
aws eks delete-cluster --name pulseguard-eks --profile pulseguard

# 5. NAT Gateway and its address — neither is removed by deleting EKS
aws ec2 delete-nat-gateway --nat-gateway-id nat-00046ef0fb9fab41c --profile pulseguard
# wait for state "deleted", then:
aws ec2 release-address --allocation-id eipalloc-04b5c7013b421c2f7 --profile pulseguard

# 6. routes that now point at a deleted gateway
aws ec2 delete-route --route-table-id rtb-01833270597ac1f15 --destination-cidr-block 0.0.0.0/0 --profile pulseguard
aws ec2 delete-route --route-table-id rtb-01253ba60d051a4fb --destination-cidr-block 0.0.0.0/0 --profile pulseguard

# 7. RDS
aws rds delete-db-instance --db-instance-identifier pulseguard-mysql \
  --skip-final-snapshot --delete-automated-backups --profile pulseguard
```

Three traps, each of which has caught people before:

- **Deleting the cluster does not delete the ALB.** Delete the Ingress and
  verify the load balancer is gone before touching the cluster.
- **Deleting the node group does not stop the EKS charge.** The control plane
  bills at ~$73/month with or without nodes.
- **Deleting EKS does not delete the NAT Gateway.** It was created by hand and
  must be removed by hand. Its Elastic IP must then be released — an
  unassociated address still bills. The ALB's two addresses are released
  automatically when the ALB goes.

---

## Known limitations

- Short-lived demonstration environment, not a production deployment
- One worker node, one NAT Gateway — neither is highly available
- NAT is a single point of failure; private subnet B egresses cross-AZ
- HTTP only — no HTTPS, no ACM certificate, no domain
- RDS is Single-AZ
- MySQL intentionally pinned to 8.0.46 to match local development
- Kafka remains local; MSK deferred on cost
- `notification-service` at zero replicas — no broker, no SMTP
- `monitor-worker` fixed at one replica — no distributed locking
- Probes use aggregate `/actuator/health`, not liveness/readiness groups
- Frontend container runs nginx as root to bind :80
- Secrets pass through the developer laptop en route to the cluster
- Deployed by hand from a laptop — no Jenkins, no GitHub webhook
- No autoscaling (HPA, Cluster Autoscaler, Karpenter)
- No Prometheus or Grafana
- No Infrastructure as Code — AWS CLI, kubectl and Helm only
