# PulseGuard — AWS Runbook

The cloud foundation PulseGuard is deployed onto. This document covers **Stage
15A: the network and the image registry** — what exists, why it is shaped that
way, what it costs, and how to remove it.

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

## What comes next

```text
15B   RDS MySQL into the private subnets, free tier
15C   deferred — Kafka stays local; MSK optional later
15D   build, tag and push the four images from your laptop
16    EKS into this same VPC
```

Stage 15C was deliberately dropped from the immediate path: Amazon MSK costs
roughly **$65–90/month** at its smallest provisioned size and has no free tier,
and PulseGuard's Kafka usage is already proven locally. Running Kafka inside
EKS, or adding MSK later, remains open — it is a cost decision rather than an
architectural one.
