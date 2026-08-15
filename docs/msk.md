# Amazon MSK — PulseGuard's managed Kafka

The broker that carries incident events from the Monitor Worker to the
Notification Service inside AWS. Local development still uses the Kafka
container in Docker Compose; this document is only about the cloud path.

```text
Cluster    pulseguard-msk
Region     us-east-1
VPC        vpc-0d216ba975638bc5b   (shared with EKS and RDS)
Version    Kafka 3.9.x
Brokers    2 × kafka.t3.small, one per AZ, 10 GiB each
Access     private only, TLS on 9094
```

> **MSK Provisioned bills continuously and has no "stopped" state.** Unlike an
> EC2 instance you cannot pause it — the only way to stop paying is to delete
> the cluster. See [Cleanup](#cleanup).

---

## Contents

- [Why MSK at all](#why-msk-at-all)
- [Cluster configuration](#cluster-configuration)
- [Two AWS restrictions worth knowing](#two-aws-restrictions-worth-knowing)
- [Networking](#networking)
- [Encryption and authentication](#encryption-and-authentication)
- [The topic](#the-topic)
- [Producer — Monitor Worker](#producer--monitor-worker)
- [Consumer — Notification Service](#consumer--notification-service)
- [Keeping local Docker working](#keeping-local-docker-working)
- [Verification](#verification)
- [Troubleshooting](#troubleshooting)
- [Cost](#cost)
- [Cleanup](#cleanup)

---

## Why MSK at all

Through Task 16 the AWS deployment had no broker. The Monitor Worker still
worked — the transactional outbox is designed so that a publish failure is
non-fatal — but events accumulated unpublished and `notification-service` ran at
zero replicas. The event-driven half of PulseGuard existed only on a laptop.

MSK closes that gap: the same producer and consumer code, unchanged, now runs
against a managed broker inside the VPC.

The alternative — running Kafka inside EKS — was rejected because a
single-node cluster running its own broker demonstrates less about cloud
architecture than a managed service does, and because a broker sharing a
`t3.medium` with four application pods would be an unrealistic test of either.

---

## Cluster configuration

```text
name              pulseguard-msk
kafka version     3.9.x
metadata mode     ZooKeeper          ← not KRaft; see below
broker type       kafka.t3.small
broker count      2
availability zones 2  (us-east-1a, us-east-1b)
storage           10 GiB EBS per broker
encryption        TLS in transit, AWS-managed KMS at rest
public access     disabled
authentication    unauthenticated (TLS transport only)
```

Two brokers across two AZs is the smallest configuration that allows a
replication factor of 2, which is what makes the topic survive losing a broker.
A single broker would have been half the price and would have made replication
meaningless.

---

## Two AWS restrictions worth knowing

Both were discovered during creation and both changed the plan.

### `kafka.t3.small` is not available with KRaft

The intended configuration was Kafka 3.9 with **KRaft** metadata. AWS rejects
that combination outright:

```text
Unsupported InstanceType specified. Valid values: [express.m7g.*, kafka.m5.*, kafka.m7g.*]
```

`3.9.x.kraft` starts at `m5.large` / `m7g.large`. The cheap burstable broker
exists only on the ZooKeeper-based `3.9.x`. The cost difference is not marginal:

| | Broker | ~Cost/day, 2 brokers |
| --- | --- | --- |
| ZooKeeper `3.9.x` | `kafka.t3.small` | **~$2.20** |
| KRaft `3.9.x.kraft` | `kafka.m7g.large` | ~$8.60 |
| KRaft `3.9.x.kraft` | `kafka.m5.large` | ~$10.10 |

ZooKeeper was chosen deliberately, to keep a short-lived demonstration
affordable. Nothing in PulseGuard's producer or consumer code is aware of the
difference — metadata management is entirely internal to the cluster. For
anything long-lived, KRaft is the right answer: ZooKeeper mode is on its way out
of Kafka generally.

### `EncryptionAtRest` cannot be requested without naming a key

Specifying an empty `EncryptionAtRest` block fails:

```text
Missing required parameter in EncryptionInfo.EncryptionAtRest: "DataVolumeKMSKeyId"
```

The fix is to **omit the block entirely**, which makes MSK use the AWS-managed
KMS key — exactly the intended outcome, and with no key to create or pay for.
Asking for the default explicitly is what breaks.

---

## Networking

Brokers sit in the same private subnets as the EKS nodes and RDS. There is no
public access, no multi-VPC connectivity and no PrivateLink.

```text
subnets   subnet-0a126abee09af775d   10.0.128.0/20   us-east-1a
          subnet-0653741028b591378   10.0.144.0/20   us-east-1b

security group  pulseguard-msk-sg
  inbound  tcp/9094  from  sg-0e154cd8cf8abedc4   (EKS cluster SG)
```

The source is a **security group reference**, not a CIDR. That matters: it
means "whatever the EKS nodes are", so it stays correct if a node is replaced
and gets a different address. It was verified rather than assumed — the node's
ENI genuinely carries `sg-0e154cd8cf8abedc4`:

```bash
aws ec2 describe-instances --profile pulseguard \
  --filters "Name=tag:eks:cluster-name,Values=pulseguard-eks" \
  --query 'Reservations[].Instances[].SecurityGroups[]'
```

No rule admits `0.0.0.0/0`, a developer IP, or the public subnet CIDRs. Kafka is
reachable from the application pods and from nowhere else.

---

## Encryption and authentication

```text
client → broker   TLS only, port 9094
in-cluster        TLS
at rest           AWS-managed KMS
authentication    none
```

**The brokers are unauthenticated.** Any client that can reach port 9094 can
read and write. That is acceptable here for one reason only: nothing can reach
port 9094 except the EKS nodes, because the brokers have no public endpoint and
the security group admits a single source.

This is a **private demonstration architecture, not a production security
model.** A real deployment would add SASL/IAM or SASL/SCRAM so that network
position alone is not authorisation — defence in depth, rather than one control
doing all the work. That was deliberately left out here to avoid pulling an
AWS-specific Kafka authentication library (`aws-msk-iam-auth`) into the
application, which would tie the code to one broker vendor.

Encryption is separate from authentication and is fully enabled: traffic between
pods and brokers is TLS, and Amazon's broker certificates validate against the
JVM's default CA trust store, so no custom truststore, JKS or PKCS12 bundle is
needed.

---

## The topic

```text
name                pulseguard.incident-events.v1
partitions          3
replication factor  2   (AWS)   /   1   (local Docker)
```

The Monitor Worker declares the topic through a Spring `NewTopic` bean, so it is
created on first connection rather than by an administrator. All three values
were already environment-driven before Task 17, which is why **no application
code changed**:

```yaml
incident-topic:            ${KAFKA_INCIDENT_TOPIC:pulseguard.incident-events.v1}
topic-partitions:          ${KAFKA_INCIDENT_TOPIC_PARTITIONS:3}
topic-replication-factor:  ${KAFKA_INCIDENT_TOPIC_REPLICATION:1}
```

The default of 1 suits a single-broker laptop. AWS overrides it to 2 through the
Kubernetes ConfigMap.

---

## Producer — Monitor Worker

```yaml
KAFKA_BOOTSTRAP_SERVERS:                    <MSK TLS bootstrap brokers>
SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL:  SSL
KAFKA_INCIDENT_TOPIC_REPLICATION:           "2"
```

`replicas: 1`, unchanged — distributed worker coordination is still not
implemented, so a second replica would duplicate every HTTP check.

The publishing path is untouched by Task 17. A monitor check, the incident state
change and the outbox insert remain one database transaction; a separate
scheduled publisher reads unpublished rows and sends them to Kafka. That
separation is what let the system keep working while no broker existed.

---

## Consumer — Notification Service

```yaml
KAFKA_BOOTSTRAP_SERVERS:                    <MSK TLS bootstrap brokers>
SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL:  SSL
KAFKA_NOTIFICATION_GROUP_ID:                pulseguard-notification-service-v1   (unchanged)
```

**The consumer group name must not change.** Kafka uses it to remember how far
this service has read; a new name replays the entire topic and re-notifies
everyone. Deduplication would catch it — `consumed_events` has a unique
constraint on `event_id` — but relying on that as routine behaviour would be
careless.

`auto-offset-reset: earliest` means a genuinely new group reads from the start
of the topic. On a freshly created MSK cluster there is no history, so this is
harmless.

---

## Keeping local Docker working

Every AWS-specific value lives in the Kubernetes ConfigMap. Docker Compose never
reads it, and sets neither `KAFKA_INCIDENT_TOPIC_REPLICATION` nor
`SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL`, so the local stack keeps the
application defaults:

```text
local   PLAINTEXT   replication factor 1   kafka:29092
AWS     SSL         replication factor 2   MSK bootstrap :9094
```

No `if (cloud)` branch exists anywhere in the code. The difference is entirely
configuration, which is the property that makes the same image deployable to
both.

---

## Verification

Bootstrap brokers are retrieved from AWS rather than written down:

```bash
aws kafka get-bootstrap-brokers --cluster-arn "$MSK_ARN" --profile pulseguard \
  --query BootstrapBrokerStringTls --output text
```

Use `BootstrapBrokerStringTls` — the plaintext field is empty on this cluster
because no plaintext listener exists.

To inspect topics, run a temporary Kafka CLI pod **inside** the VPC; the brokers
are unreachable from a laptop by design.

```bash
kubectl run kafka-cli -n pulseguard --rm -it --restart=Never \
  --image=bitnami/kafka:3.9 -- bash

# inside the pod:
echo "security.protocol=SSL" > /tmp/client.properties
kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --command-config /tmp/client.properties --list
kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --command-config /tmp/client.properties \
  --describe --topic pulseguard.incident-events.v1
kafka-consumer-groups.sh --bootstrap-server "$BOOTSTRAP" --command-config /tmp/client.properties \
  --describe --group pulseguard-notification-service-v1
```

Delete the pod afterwards. No Kafka UI is deployed — it would need an ingress,
and there is nothing it shows that these commands do not.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `Connection to node -1 could not be established` | Wrong bootstrap string, or the security group does not admit the pod's node |
| TLS handshake failure | `SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL` not set to `SSL`, so the client is speaking plaintext to a TLS listener |
| `TimeoutException` on topic creation | Replication factor exceeds broker count — RF 2 needs both brokers healthy |
| Consumer sees no records | Consumer group already committed past them; check with `kafka-consumer-groups.sh --describe` |
| Events publish but no email | Kafka is working — look at `notification_deliveries.status` and the SMTP settings instead |

The producer retries a lost broker roughly every 50 ms with no backoff, which is
noisy in logs. Harmless over a short demonstration; it would want tuning before
anything long-lived.

---

## Cost

```text
2 × kafka.t3.small     ~$0.0456/broker-hour   ≈ $2.19/day
20 GiB EBS total       ~$0.10/GB-month        ≈ $0.07/day
data transfer          in-VPC, same AZ mostly  negligible
```

**Roughly $2.25/day, about $9 over four days.** An estimate from published
rates, not a bill.

That is on top of the existing EKS, NAT, ALB and RDS charges — MSK takes the
total AWS burn to roughly **$8/day**.

---

## Cleanup

MSK has no stopped state. Delete it when the demonstration is over.

```bash
aws kafka delete-cluster --cluster-arn "$MSK_ARN" --profile pulseguard

# deletion takes several minutes; wait for the cluster to disappear
aws kafka describe-cluster --cluster-arn "$MSK_ARN" --profile pulseguard \
  --query 'ClusterInfo.State' --output text     # eventually: not found

# only once the brokers are gone will the security group release
aws ec2 delete-security-group --group-id <pulseguard-msk-sg> --profile pulseguard
```

The security group cannot be deleted while broker ENIs still reference it, so it
comes after the cluster, not before. In the full teardown order MSK sits between
deleting the EKS cluster and deleting the NAT Gateway — see
[`kubernetes.md`](kubernetes.md).
