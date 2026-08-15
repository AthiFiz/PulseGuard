# PulseGuard on Kubernetes

Plain Kubernetes manifests for the EKS deployment. No Helm chart, no Kustomize
overlay — the objects are written out so the deployment is readable directly.

Full narrative, including how the cluster and its networking were built, is in
[`../docs/kubernetes.md`](../docs/kubernetes.md).

---

## What is here

| File | Purpose |
| --- | --- |
| `namespace.yaml` | The `pulseguard` namespace |
| `configmap.yaml` | Non-secret runtime config: RDS URL, SSRF policy |
| `frontend-nginx-configmap.yaml` | Kubernetes-specific nginx server block |
| `secret.example.yaml` | **Template only** — placeholders, never real values |
| `control-api-deployment.yaml` / `-service.yaml` | Control API + ClusterIP |
| `monitor-worker-deployment.yaml` | Monitor Worker, `replicas: 1` |
| `notification-service-deployment.yaml` | Notification Service, `replicas: 0` |
| `frontend-deployment.yaml` / `-service.yaml` | Frontend + ClusterIP |
| `frontend-ingress.yaml` | ALB Ingress — the only public entry point |

---

## Prerequisites

The cluster, its NAT egress, the private RDS instance and the AWS Load Balancer
Controller must already exist. Creating them is documented in
[`../docs/aws.md`](../docs/aws.md); these manifests assume them.

```bash
aws eks update-kubeconfig --name pulseguard-eks --region us-east-1 --profile pulseguard

# Always confirm the target before applying anything.
kubectl config current-context
```

---

## Deploying

The Secret is created from AWS Secrets Manager rather than from a file, so the
RDS password never lands in this repository or in shell history.

```bash
kubectl apply -f namespace.yaml

SECRET_ARN=$(aws rds describe-db-instances --db-instance-identifier pulseguard-mysql \
    --profile pulseguard --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)
DB_USER=$(aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --profile pulseguard \
    --query SecretString --output text | python3 -c 'import sys,json;print(json.load(sys.stdin)["username"])')
DB_PASS=$(aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --profile pulseguard \
    --query SecretString --output text | python3 -c 'import sys,json;print(json.load(sys.stdin)["password"])')

kubectl create secret generic pulseguard-secrets -n pulseguard \
    --from-literal=DB_USERNAME="$DB_USER" \
    --from-literal=DB_PASSWORD="$DB_PASS" \
    --from-literal=JWT_SECRET="$(openssl rand -base64 48)"

kubectl apply -f configmap.yaml -f frontend-nginx-configmap.yaml

# Control API first — it owns the Flyway migrations, and the worker expects the
# schema to exist. Waiting on rollout rather than sleeping.
kubectl apply -f control-api-deployment.yaml -f control-api-service.yaml
kubectl rollout status deployment/control-api -n pulseguard --timeout=10m

kubectl apply -f monitor-worker-deployment.yaml
kubectl apply -f notification-service-deployment.yaml
kubectl apply -f frontend-deployment.yaml -f frontend-service.yaml
kubectl apply -f frontend-ingress.yaml

# The ALB takes a couple of minutes to provision and register targets.
kubectl get ingress -n pulseguard -w
```

---

## Things that are deliberate

**`monitor-worker` stays at `replicas: 1`.** Distributed coordination is not
implemented. A second replica would duplicate every HTTP check and advance
consecutive-failure counters at twice the intended rate, firing incidents early.
Scale the frontend or Control API instead.

**`notification-service` runs at `replicas: 0`.** It consumes Kafka and sends
email; neither exists in AWS. Kafka stays local, so the notification flow is
demonstrated on Docker Compose, not here.

**The frontend nginx config is overridden.** The image's own config uses
`resolver 127.0.0.11` — Docker's embedded DNS, which does not exist in a pod.
Mounting a replacement is deployment configuration and avoids rebuilding the
image. See the comments in `frontend-nginx-configmap.yaml`.

**Only the frontend is exposed.** Control API, worker and notification service
are all `ClusterIP` or have no Service at all. The single ALB in front of the
frontend is the only route in from the internet, and nginx proxies `/api` to the
Control API from inside the cluster.

**Images are pinned to git SHAs, never `:latest`.** The frontend specifically
uses `4970e68-patched` — the rebuild that cleared 1 CRITICAL and 15 HIGH CVEs
found by ECR scan-on-push. The plain `4970e68` frontend tag still exists in ECR
and must not be deployed.

---

## Teardown

Order matters. Delete the Ingress **first** and confirm the ALB is gone —
deleting the cluster out from under the controller can orphan the load balancer
and its target groups, which keep billing.

```bash
kubectl delete -f frontend-ingress.yaml
aws elbv2 describe-load-balancers --profile pulseguard \
  --query "LoadBalancers[?VpcId=='vpc-0d216ba975638bc5b'].LoadBalancerName"   # expect []

kubectl delete namespace pulseguard
```

The rest of the teardown — node group, cluster, NAT Gateway, Elastic IP — is in
[`../docs/aws.md`](../docs/aws.md).
