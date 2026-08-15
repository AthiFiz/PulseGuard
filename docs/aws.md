# PulseGuard — AWS Runbook

The cloud foundation PulseGuard is deployed onto — what exists, why it is shaped
that way, what it costs, and how to remove it. It covers Stages **15A** (network
and registry), **15B** (RDS), **15D** (images), **16** (EKS) and **17** (MSK).

```text
Account   423151037862  ("athif-aws")
Region    us-east-1
Profile   pulseguard        (aws --profile pulseguard ...)
```

---

## Contents

- [What exists after 15A](#what-exists-after-15a)
- [The network, and why it looks like this](#the-network-and-why-it-looks-like-this)
- [The NAT Gateway decision](#the-nat-gateway-decision)
- [Reaching the database from your laptop](#reaching-the-database-from-your-laptop)
- [ECR](#ecr)
- [What this costs](#what-this-costs)
- [Tearing it down](#tearing-it-down)
- [Doing it by hand in the console](#doing-it-by-hand-in-the-console)
- [Stage 15B — RDS MySQL](#stage-15b--rds-mysql)
- [Stage 15D — images in ECR](#stage-15d--images-in-ecr)
- [Stage 16 — EKS, NAT and the RDS privacy correction](#stage-16--eks-nat-and-the-rds-privacy-correction)
- [Stage 17 — Amazon MSK](#stage-17--amazon-msk)
- [Tearing down Stage 16](#tearing-down-stage-16)
- [What comes next](#what-comes-next)

---

## What exists after 15A

```text
VPC  pulseguard-vpc            vpc-0d216ba975638bc5b     10.0.0.0/16
     DNS hostnames: on         DNS resolution: on
     │
     ├── igw  pulseguard-igw   igw-05ce15888de664481     attached
     │
     ├── us-east-1a
     │     public1   10.0.0.0/20     auto-assign public IP
     │     private1  10.0.128.0/20
     │
     └── us-east-1b
           public2   10.0.16.0/20    auto-assign public IP
           private2  10.0.144.0/20

route tables
     pulseguard-rtb-public              0.0.0.0/0 → igw     ← both public subnets
     pulseguard-rtb-private1-us-east-1a local only          ← private1
     pulseguard-rtb-private2-us-east-1b local only          ← private2

security groups
     pulseguard-rds-sg   sg-09aee6e90c346740d
         inbound: tcp/3306 from <your laptop IP>/32 only

ECR repositories  (all: scan-on-push, AES256, mutable tags)
     423151037862.dkr.ecr.us-east-1.amazonaws.com/pulseguard-control-api
                                                 /pulseguard-monitor-worker
                                                 /pulseguard-notification-service
                                                 /pulseguard-frontend
```

**Nothing here is billable.** No NAT Gateway, no Elastic IP, no compute, no
images stored yet. See [what this costs](#what-this-costs).

> The account also contains a pre-existing **default VPC** (`172.31.0.0/16`) and
> a stopped `t3.large` EC2 instance named `docker-vm`, neither related to
> PulseGuard. They are deliberately untouched.

---

## The network, and why it looks like this

### One VPC for everything

RDS (15B) and EKS (16) both live inside a VPC, and they have to reach each
other. Putting them in **the same VPC from the start** avoids the classic
rework: an RDS instance created in the default VPC, an EKS cluster created in
its own, and then VPC peering or a rebuild to connect them.

`10.0.0.0/16` gives 65,536 addresses — far more than this project needs, but a
/16 is the conventional choice and leaves room to subdivide cleanly.

### Two Availability Zones

Not for high availability at this stage — for **eligibility**:

- RDS requires a *DB subnet group* spanning **at least two AZs**, even for a
  single-AZ instance
- EKS wants at least two for its control plane

So two AZs is the floor, not a luxury. `us-east-1a` and `us-east-1b`.

### Public and private subnets

| | CIDR | Purpose |
| --- | --- | --- |
| `public1` / `public2` | `10.0.0.0/20`, `10.0.16.0/20` | Anything needing an inbound route from the internet — load balancers, and (in Task 16) EKS nodes |
| `private1` / `private2` | `10.0.128.0/20`, `10.0.144.0/20` | RDS. No route to the internet at all |

The gap between `10.0.16.0/20` and `10.0.128.0/20` is deliberate — it leaves the
middle of the range free for more subnets later without renumbering.

Public subnets have **auto-assign public IPv4 enabled**; private subnets do not.
That single attribute is most of what makes a subnet "public" in practice,
alongside its route table.

### Two private route tables, not one

This is the least obvious choice here. Both private route tables are identical
today — local routes only, no default route — so one shared table would work.

They are split **per Availability Zone** because of what happens if a NAT
Gateway is ever added. A NAT Gateway lives in one AZ. With a single shared
private route table, *both* AZs would route through that one gateway, sending
`us-east-1b` traffic across an AZ boundary — which costs cross-AZ data transfer
and adds latency, silently. Per-AZ tables mean each AZ can point at its own
gateway, or none.

Splitting them now costs nothing. Splitting them later means editing routes
while something depends on them.

### EKS subnet tags, applied early

Every subnet carries a tag Kubernetes looks for:

```text
public subnets    kubernetes.io/role/elb           = 1
private subnets   kubernetes.io/role/internal-elb  = 1
```

These tell the AWS Load Balancer Controller which subnets it may place load
balancers in. Without them, a `Service` of type `LoadBalancer` in Task 16 fails
with an unhelpful "could not find any suitable subnets" error. The tags are
free, cluster-agnostic, and cheaper to add now than to debug later.

---

## The NAT Gateway decision

**There is no NAT Gateway, deliberately.**

The AWS VPC wizard offers to create one by default, and it is the single most
common surprise line on a small AWS bill: roughly **$0.045/hour plus $0.045 per
GB processed** — about **$32/month before any traffic**.

A NAT Gateway exists to let resources in *private* subnets make *outbound*
connections to the internet. Ask what actually needs that here:

| Resource | Needs outbound internet? |
| --- | --- |
| RDS MySQL | **No.** It accepts connections; it does not make them |
| EKS nodes (Task 16) | Depends on where they are placed — decided in Task 16, not assumed now |

So nothing in 15A or 15B needs one. Task 16 makes that call explicitly, and if
nodes go in public subnets or reach AWS services through VPC endpoints, it may
never be needed at all.

**If you ever see a NAT Gateway in this account, something created it by
accident.** Check with:

```bash
aws ec2 describe-nat-gateways --profile pulseguard \
  --filter "Name=vpc-id,Values=vpc-0d216ba975638bc5b" \
  --query 'length(NatGateways)'
```

That should return `0`.

---

## Reaching the database from your laptop

A consequence of having no NAT and putting RDS in private subnets: **your laptop
cannot reach the database**. Private subnets have no inbound route from the
internet either.

That matters because 15B wants to verify the schema — run Flyway, confirm the
tables exist — and none of that is possible against an unreachable endpoint.

Three ways out were considered:

| Option | Verdict |
| --- | --- |
| **(a)** RDS publicly accessible, security group locked to one IP | **Chosen.** Verifiable immediately; exposure limited to a single address |
| **(b)** Private RDS, reachable only from EKS in Task 16 | Most correct, but leaves 15B unverifiable for an entire stage |
| **(c)** Private RDS plus an EC2 bastion | Correct and verifiable, but ~$8/month and another host to secure |

**(a) is a deliberate, temporary trade** — chosen for a learning project where
being able to *see* the schema matters, and to be tightened in Task 16 once EKS
can reach the database from inside the VPC.

The security group already reflects it:

```text
pulseguard-rds-sg   inbound  tcp/3306  from <your laptop IP>/32
```

> **Your home IP changes.** When it does, RDS stops answering and the fix is to
> update this one rule. Never widen it to `0.0.0.0/0` — that publishes a
> database to the entire internet, and automated scanners find open 3306 within
> minutes.

```bash
# what does the rule allow today?
aws ec2 describe-security-groups --group-ids sg-09aee6e90c346740d \
  --profile pulseguard --query 'SecurityGroups[0].IpPermissions'

# what is my IP now?
curl -s https://checkip.amazonaws.com
```

---

## ECR

Four private repositories, one per deployable image, matching what
`docker compose build` already produces:

```text
pulseguard-control-api
pulseguard-monitor-worker
pulseguard-notification-service
pulseguard-frontend
```

Each is configured with:

- **Scan on push** — ECR checks each pushed image against the CVE database
  automatically. Free, and it means vulnerability findings arrive without anyone
  remembering to ask.
- **AES256 encryption at rest** — the default; no KMS key to manage or pay for.
- **Mutable tags** — so `:latest` can be re-pointed during development.
  Immutable tags are better discipline for real deployments and are worth
  revisiting when images start being deployed rather than experimented with.

Repositories cost nothing while empty. Pushing images is Stage 15D.

---

## What this costs

**Stage 15A: nothing.** Every resource created is free:

| Resource | Cost |
| --- | --- |
| VPC, subnets, route tables | Free |
| Internet Gateway | Free (only NAT Gateways bill) |
| Security groups | Free |
| ECR repositories, empty | Free |

What will cost, later:

| Stage | Resource | Approx. monthly |
| --- | --- | --- |
| 15B | RDS `db.t3.micro` | **$0** on the 12-month free tier, then ~$15 |
| 15D | ECR storage | ~$0.10/GB — four images ≈ $0.15 |
| 16 | EKS control plane | **~$73**, flat, regardless of use |
| 16 | EKS worker nodes | ~$30 for 2 × `t3.small` |

> The account budget is **$15/month** with alerts at 85%, 100% and forecast
> 100%. **Task 16 will exceed it**, and that needs to be a conscious decision
> before the cluster is created — not a surprise email.

---

## Tearing it down

Nothing in 15A costs anything, so there is no financial reason to remove it. If
you want to start over, delete in this order — AWS refuses to delete things
still referenced by others:

```bash
P="--profile pulseguard"
VPC=vpc-0d216ba975638bc5b

# 1. ECR repositories (--force also deletes any images inside)
for r in control-api monitor-worker notification-service frontend; do
  aws ecr delete-repository --repository-name pulseguard-$r --force $P
done

# 2. security groups (not the VPC's own "default" group — that cannot be deleted)
aws ec2 delete-security-group --group-id sg-09aee6e90c346740d $P

# 3. subnets
aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC" \
  --query 'Subnets[].SubnetId' --output text $P |
  tr '\t' '\n' | xargs -I{} aws ec2 delete-subnet --subnet-id {} $P

# 4. route tables (the main one is deleted with the VPC)
#    delete only tables that are not Main

# 5. detach and delete the internet gateway
aws ec2 detach-internet-gateway --internet-gateway-id igw-05ce15888de664481 --vpc-id $VPC $P
aws ec2 delete-internet-gateway --internet-gateway-id igw-05ce15888de664481 $P

# 6. the VPC itself
aws ec2 delete-vpc --vpc-id $VPC $P
```

**Do not delete the default VPC** (`172.31.0.0/16`). It is unrelated, some AWS
features expect one to exist, and recreating it is awkward.

---

## Doing it by hand in the console

Everything above was created through the AWS CLI. The equivalent click-by-click
walkthrough — for learning what each screen does — is kept as a separate
document; see the Stage 15A console guide.

The short version, if you only want the shape of it:

1. **VPC → Create VPC → "VPC and more"** (not "VPC only")
2. Name `pulseguard`, IPv4 `10.0.0.0/16`, no IPv6
3. **2 AZs, 2 public subnets, 2 private subnets**
4. **NAT gateways: None** ← the one that costs money if you leave the default
5. **VPC endpoints: None**
6. DNS hostnames and DNS resolution both enabled
7. Then **ECR → Create repository** four times, with *Scan on push* enabled

The wizard produces the same VPC, subnets, internet gateway and route tables in
one step. The one thing it does differently is creating a **single** private
route table shared across AZs — see [above](#two-private-route-tables-not-one)
for why this project splits them per AZ instead.

---

## Stage 15B — RDS MySQL

```text
pulseguard-mysql
  engine        MySQL 8.0.46          ← identical to the Compose container
  class         db.t3.micro           ← the free-tier eligible size
  storage       20 GiB gp2, encrypted, autoscaling OFF
  deployment    single-AZ
  backups       7 days
  password      managed by AWS Secrets Manager
  database      pulseguard
  user          pulseguard_admin
```

**Matching 8.0.46 exactly is deliberate.** Collation defaults, reserved words
and JSON function behaviour all shift between MySQL minor versions, so pinning
the cloud database to the version the application is developed against removes
a whole category of "worked locally" failure.

**Storage autoscaling is off on purpose.** It is enabled by default and will
silently grow past the 20 GiB free allowance.

### The password nobody typed

Choosing *Managed in AWS Secrets Manager* means the master password was
generated by AWS, never displayed during setup, and never written to this
repository. It is fetched by IAM-authorised API call:

```bash
SECRET_ARN=$(aws rds describe-db-instances --db-instance-identifier pulseguard-mysql \
  --profile pulseguard --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)

aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --profile pulseguard \
  --query SecretString --output text
```

> Secrets Manager bills about **$0.40/month** per secret. It is the one part of
> Stage 15B that is not free-tier covered.

### Verifying the schema

The check that matters is not "did the database start" but "does PulseGuard's
schema exist on it and match the code". That was verified by running the actual
`pulseguard-control-api` image — the same artifact later pushed to ECR — against
the RDS endpoint:

```text
Successfully applied 8 migrations to schema `pulseguard`, now at version v8
Started ControlApiApplication in 181.038 seconds
{"groups":["liveness","readiness"],"status":"UP"}
```

The second line carries the real proof. The application runs Hibernate with
`ddl-auto=validate`, so it compares every JPA entity against the real tables and
refuses to start on any mismatch. A successful start is a machine-checked
assertion that Flyway's output matches what the code expects.

Confirmed independently from the database side: ten tables, all InnoDB and
utf8mb4, `flyway_schema_history` at version 8 with zero failed rows.

---

## Stage 15D — images in ECR

Four images, each tagged twice — `latest` for convenience and the short git SHA
for truth:

```bash
aws ecr get-login-password --region us-east-1 --profile pulseguard \
  | docker login --username AWS --password-stdin \
      423151037862.dkr.ecr.us-east-1.amazonaws.com

REG=423151037862.dkr.ecr.us-east-1.amazonaws.com
SHA=$(git rev-parse --short HEAD)
for s in control-api monitor-worker notification-service frontend; do
  docker tag "pulseguard-$s:local" "$REG/pulseguard-$s:$SHA"
  docker push "$REG/pulseguard-$s:$SHA"
done
```

`latest` means something different after every push and cannot answer "which
commit is running?". The SHA tag can, which is the question that actually
matters when a pod is misbehaving. Both tags resolve to one digest, so the
second costs no storage.

### Scan-on-push found something immediately

| Image | Critical | High | Medium | Low |
| --- | --- | --- | --- | --- |
| control-api | 0 | 0 | 6 | 0 |
| monitor-worker | 0 | 0 | 6 | 0 |
| notification-service | 0 | 0 | 0 | 0 |
| **frontend** | **1** | **15** | 31 | 3 |

Every frontend finding was in OS packages of its `nginx:1.29-alpine` base —
openssl (7, including the critical `CVE-2026-34182`), curl, expat, libxml2,
nghttp2 — and none in application code or npm dependencies.

**`docker build --pull` did not fix it.** Pulling the base reported *"Image is
up to date"*: the nginx tag itself had not been rebuilt, so a fresh pull
returned the identical vulnerable packages. The base image lags Alpine's patch
stream. The fix was one line in the runtime stage:

```dockerfile
RUN apk upgrade --no-cache
```

which pulls patched packages straight from Alpine's repository. The rebuilt
image re-scanned at **zero findings of any severity** and was pushed as
`4970e68-patched`. The vulnerable image still exists under the plain `4970e68`
tag and must never be deployed.

---

## Stage 16 — EKS, NAT and the RDS privacy correction

Stage 16 changes the shape of the network: application workloads move into
private subnets, and the temporary Stage 15B exposure is removed.

### The NAT Gateway that 15A deliberately avoided

15A created no NAT Gateway because nothing needed outbound internet access.
Private EKS nodes do — they must register with the EKS control plane and pull
images from ECR — so exactly one was created:

```text
pulseguard-nat   nat-00046ef0fb9fab41c   public subnet A (us-east-1a)
                 Elastic IP  eipalloc-04b5c7013b421c2f7

route tables
  pulseguard-rtb-public     0.0.0.0/0 → igw    (unchanged)
  pulseguard-rtb-private1   0.0.0.0/0 → nat    (added)
  pulseguard-rtb-private2   0.0.0.0/0 → nat    (added)
```

**One NAT, not two, and that is a real trade-off.** A production multi-AZ system
would run one per Availability Zone. This one is a single point of failure, and
traffic from private subnet B crosses an AZ boundary to reach it — which adds
latency and cross-AZ data transfer charges. At roughly $32/month each, a second
gateway doubles the largest line on this bill to buy redundancy a three-day
demonstration cannot use.

### The mandatory RDS privacy correction

Stage 15B deliberately put RDS in public subnets with `PubliclyAccessible=true`
and a laptop `/32` rule, so the schema could be verified before any cluster
existed. Stage 16 removes that.

| | Before (15B) | After (16) |
| --- | --- | --- |
| Subnet group | `pulseguard-db-subnet-group` — **public** A/B | `pulseguard-db-private-subnet-group` — **private** A/B |
| PubliclyAccessible | `true` | **`false`** |
| Port 3306 source | `112.134.240.107/32` (laptop) | **EKS cluster SG** `sg-0e154cd8cf8abedc4` |

**AWS will not move a running instance between subnet groups inside one VPC.**
`ModifyDBInstance` rejects it outright ("Choose a DB subnet group in different
VPC"), and removing the public subnets from the existing group fails too because
the instance's network interface is still using one. The instance held no
application data — only the Flyway schema, which the Control API rebuilds on
startup — so it was deleted and recreated directly into the private subnet
group. A snapshot-and-restore would have preserved data at the cost of a new
password and roughly ten more minutes.

> **Your laptop can no longer reach the database, and that is the point.** Do
> not re-add a `/32` rule. To inspect it, tunnel through the cluster instead:
>
> ```bash
> kubectl run mysql-tunnel -n pulseguard --image=alpine/socat --restart=Never -- \
>   tcp-listen:3306,fork,reuseaddr \
>   tcp-connect:pulseguard-mysql.cc5sk4k4err6.us-east-1.rds.amazonaws.com:3306
> kubectl port-forward -n pulseguard pod/mysql-tunnel 13306:3306
> ```
>
> Then point any MySQL client at `127.0.0.1:13306`. Nothing is publicly
> exposed, and the tunnel exists only while the command runs. Delete the pod
> afterwards.

---

## Stage 17 — Amazon MSK

The gap Stage 16 left open: with no broker in AWS, incident events accumulated
unpublished in the outbox and `notification-service` ran at zero replicas. MSK
closes it.

```text
pulseguard-msk
  kafka version   3.9.x            (ZooKeeper metadata — see below)
  brokers         2 × kafka.t3.small, one per AZ
  storage         10 GiB EBS each
  subnets         private A + private B  (same as EKS and RDS)
  security group  pulseguard-msk-sg → tcp/9094 from the EKS cluster SG only
  encryption      TLS in transit · AWS-managed KMS at rest
  public access   disabled
  authentication  none — TLS transport only
```

**KRaft was not available at this price.** AWS rejects `kafka.t3.small` on
`3.9.x.kraft`; that combination starts at `m5.large`/`m7g.large`, which is
roughly **four times** the daily cost. ZooKeeper mode was chosen deliberately to
keep a short-lived demonstration affordable. Nothing in the application is aware
of the difference.

**The brokers are unauthenticated**, which is safe here only because they have
no public endpoint and their security group admits exactly one source — the EKS
nodes. That is a private demo architecture, **not** a production security model;
a real deployment would add SASL/IAM so that network position alone is not
authorisation.

Full detail — topic configuration, producer and consumer settings, verification
commands and troubleshooting — is in **[msk.md](msk.md)**.

> **MSK Provisioned has no stopped state.** Unlike EC2 you cannot pause it; the
> only way to stop paying is to delete the cluster. It adds roughly
> **$2.25/day**, taking the total AWS burn to about **$8/day**.

---

## Tearing down Stage 16

**Order matters.** These resources bill by the hour and some of them are created
by controllers rather than by you, so deleting the cluster first can orphan
them.

```bash
# 1. Ingress FIRST — this is what deletes the ALB
kubectl delete ingress frontend -n pulseguard

# 2. confirm the ALB actually went away before continuing
aws elbv2 describe-load-balancers --profile pulseguard \
  --query "LoadBalancers[?VpcId=='vpc-0d216ba975638bc5b'].LoadBalancerName"   # expect []

# 3. the rest of the workloads
kubectl delete namespace pulseguard

# 4. node group, then cluster — deleting only the node group leaves the
#    ~$73/month control-plane charge running
aws eks delete-nodegroup --cluster-name pulseguard-eks --nodegroup-name pulseguard-nodegroup --profile pulseguard
aws eks wait nodegroup-deleted --cluster-name pulseguard-eks --nodegroup-name pulseguard-nodegroup --profile pulseguard
aws eks delete-cluster --name pulseguard-eks --profile pulseguard

# 5. MSK — no stopped state; deleting is the only way to stop paying.
#    Its security group cannot go until the broker ENIs are released, so the
#    group deletion comes after the cluster is fully gone.
aws kafka delete-cluster --cluster-arn "$MSK_ARN" --profile pulseguard
aws ec2 delete-security-group --group-id <pulseguard-msk-sg> --profile pulseguard

# 6. NAT Gateway — deleting EKS does NOT remove it
aws ec2 delete-nat-gateway --nat-gateway-id nat-00046ef0fb9fab41c --profile pulseguard
# wait for state "deleted", then release the address
aws ec2 release-address --allocation-id eipalloc-04b5c7013b421c2f7 --profile pulseguard

# 7. the private default routes now point at a deleted gateway
aws ec2 delete-route --route-table-id rtb-01833270597ac1f15 --destination-cidr-block 0.0.0.0/0 --profile pulseguard
aws ec2 delete-route --route-table-id rtb-01253ba60d051a4fb --destination-cidr-block 0.0.0.0/0 --profile pulseguard

# 8. RDS — stop (max 7 days) or delete
aws rds delete-db-instance --db-instance-identifier pulseguard-mysql \
  --skip-final-snapshot --delete-automated-backups --profile pulseguard
```

Three traps worth restating:

- **Deleting the cluster does not delete the ALB.** Delete the Ingress first and
  verify.
- **Deleting the node group does not stop the EKS charge.** The control plane
  bills whether or not any node exists.
- **Deleting EKS does not delete the NAT Gateway.** It was created by hand and
  must be removed by hand, along with its Elastic IP — an unassociated address
  keeps billing.

---

## What comes next

```text
15C   deferred — Kafka stays local; MSK optional later
17+   GitHub → Jenkins → ECR → EKS automatic deployment
      multi-worker coordination
      Prometheus / Grafana
```

Stage 15C was deliberately dropped: Amazon MSK costs roughly **$65–90/month** at
its smallest provisioned size and has no free tier, and PulseGuard's Kafka usage
is already proven locally. Running Kafka inside EKS, or adding MSK later,
remains open — a cost decision rather than an architectural one.
