# Observability — Prometheus and Grafana

PulseGuard watches other people's APIs. This is what watches PulseGuard.

```text
 Control API ──────┐
 Monitor Worker ───┤  /actuator/prometheus
 Notification ─────┤
                   ├──▶  Prometheus  ──▶  Grafana
 kube-state-metrics┤       (24h, in-memory)   "PulseGuard Overview"
 node-exporter ────┘
```

Nothing here is part of the runtime path. Prometheus scraping PulseGuard does
not make PulseGuard work; removing the whole `monitoring` namespace leaves the
application running exactly as before.

```text
namespace   monitoring
release     pulseguard-monitoring
chart       kube-prometheus-stack 88.3.0
access      kubectl port-forward — nothing public
```

---

## Contents

- [What it is made of](#what-it-is-made-of)
- [How the applications expose metrics](#how-the-applications-expose-metrics)
- [Why the metrics endpoint is not public](#why-the-metrics-endpoint-is-not-public)
- [How Prometheus finds the applications](#how-prometheus-finds-the-applications)
- [Custom PulseGuard metrics](#custom-pulseguard-metrics)
- [The dashboard](#the-dashboard)
- [Installation](#installation)
- [Opening Grafana](#opening-grafana)
- [Opening Prometheus](#opening-prometheus)
- [Useful queries](#useful-queries)
- [Resource footprint](#resource-footprint)
- [Troubleshooting](#troubleshooting)
- [Cost](#cost)
- [Cleanup](#cleanup)
- [Known limitations](#known-limitations)

---

## What it is made of

Installed from `kube-prometheus-stack`, pinned to chart **88.3.0**:

| Component | Purpose |
| --- | --- |
| Prometheus Operator | Turns `ServiceMonitor` objects into scrape configuration |
| Prometheus | Scrapes and stores the metrics |
| Grafana | Displays them |
| kube-state-metrics | Kubernetes object state — pod ready, restart counts |
| node-exporter | Node CPU, memory, disk, network |

**Alertmanager is disabled.** Nothing would receive its alerts — there is no
email routing, no Slack, no PagerDuty, and deliberately so. Running it would
consume memory on a node that has little to spare in order to deliver
notifications nowhere. PulseGuard already demonstrates incident notification;
that is what the product does. This stack exists to make the system visible.

Four control-plane scrape jobs are also disabled — `kubeEtcd`,
`kubeControllerManager`, `kubeScheduler`, `kubeProxy`. EKS runs and hides the
control plane, so those targets can never come up. Leaving them enabled
produces permanently red targets, which trains you to ignore red targets.

---

## How the applications expose metrics

All three Spring Boot services already used Actuator. Two changes made them
scrapeable:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    tags:
      application: pulseguard-control-api   # and the other two
```

Micrometer was already collecting HTTP, JVM, GC and connection-pool metrics —
Actuator pulls it in. The registry above only adds the ability to *render* them
in the text format Prometheus scrapes.

**The exposure list is `health,prometheus`, never `*`.** A wildcard would also
publish `/actuator/env`, `/configprops`, `/beans` and `/heapdump` — which leak
configuration and can dump the entire JVM heap, secrets included.

The `application` tag is attached to every metric from each process, so a single
query can separate the three backends without depending on pod names that change
on every rollout.

---

## Why the metrics endpoint is not public

`/actuator/prometheus` is unauthenticated — Prometheus scrapes on a timer and
has no way to hold a JWT. That is safe only because the endpoint cannot be
reached from outside the cluster, and that took **two** independent controls.

The subtle part: the frontend's nginx proxies `/api/` to the Control API and
passes the original URI through unchanged. So a request for
`/api/actuator/prometheus` arrives at Spring as `/api/actuator/prometheus` and
is answered by Spring, not by nginx. Before this stage it returned **401** —
refused, but only because a Security matcher happened not to match.

That is a thin thing to rest on when the alternative is publishing JVM internals
to the internet. So:

1. **nginx returns 404 for `/api/actuator/`** — the prefix is never proxied.
   This block is longer than `/api/`, and nginx prefers the longest match.
2. **Spring Security permits the exact path `/actuator/prometheus`** — which
   `/api/actuator/prometheus` does not match.

Verified from outside:

```console
$ curl -o /dev/null -w '%{http_code}' http://<alb>/api/actuator/prometheus
404
$ curl -o /dev/null -w '%{http_code}' http://<alb>/api/actuator/env
404
```

The Services are all `ClusterIP`. Only something already inside the cluster can
reach the application ports at all.

---

## How Prometheus finds the applications

Through `ServiceMonitor` objects, not by editing scrape config:

```text
k8s/monitoring/metrics-services.yaml   Services for the worker and notification
                                       service, which had none — neither takes
                                       application traffic
k8s/monitoring/service-monitors.yaml   one ServiceMonitor per backend
```

The Control API already had a Service. The other two never did, because nothing
routes to them; they exist now purely so Prometheus has a target.

One chart default is worth knowing about, because it is the most common reason
a Spring Boot service never appears in Prometheus:

```yaml
serviceMonitorSelectorNilUsesHelmValues: false
```

Without it, Prometheus only picks up ServiceMonitors carrying its own Helm
release label — and PulseGuard's are not chart-managed, so they would be
silently ignored.

Scrape interval is **30s** with a **10s** timeout. This is a four-pod system on
one node; scraping every second would cost more CPU than the thing being
measured.

---

## Custom PulseGuard metrics

Micrometer gives HTTP, JVM and pool metrics for free. What it cannot know is
what this application is *for*. Four counters answer that:

| Metric | Labels | Meaning |
| --- | --- | --- |
| `pulseguard_monitor_checks_total` | `result` = SUCCESS/FAILURE | Checks executed and stored |
| `pulseguard_incidents_opened_total` | — | Outages detected |
| `pulseguard_incidents_resolved_total` | — | Recoveries detected |
| `pulseguard_outbox_publish_total` | `result` = success/failure | Events published to MSK |
| `pulseguard_notification_delivery_total` | `status` = sent/retrying/failed | Email outcomes |

### Why the labels are so plain

Every label value comes from a small fixed set. Deliberately absent are
`monitorId`, `projectId`, incident ids, URLs and **email addresses**.

Prometheus creates one time series per distinct label combination, so labelling
by monitor id would mean a new series per monitor, forever — the cardinality
explosion that makes a Prometheus instance fall over. Those identifiers already
live in the database and the logs, which is where "which one?" belongs.

Email addresses have a second problem: metrics are typically the least
access-controlled surface a system has, and an address is personal data.

### Why they cannot break PulseGuard

Incrementing a Micrometer counter is a lock-free add to an in-memory number. It
performs no I/O and throws nothing in normal operation, so these calls sit inside
the same transaction as the business writes without adding a failure mode.
Observability must not be able to stop a monitor being marked DOWN.

Counters are registered at **startup**, not on first use. A counter created
lazily is absent from the scrape until the event happens, and a panel querying
it shows "No data" — which reads as a broken dashboard rather than "this has not
happened yet".

---

## The dashboard

**PulseGuard Overview**, provisioned from
`k8s/monitoring/pulseguard-dashboard-configmap.yaml`. Six sections:

| Section | Panels |
| --- | --- |
| Application health | Three up/down stats, checks, incidents, emails in the last hour |
| HTTP / API | Request rate by status, p95/p50 latency, 4xx/5xx, HikariCP pool |
| JVM | Heap, process CPU, GC pause rate, uptime |
| Monitoring engine | Checks by result, outbox publication |
| Incidents and notifications | Opened vs resolved, email by outcome |
| Kubernetes | Pod ready, restarts, pod CPU/memory, node CPU/memory |

It is generated by `scripts/gen_dashboard.py` — edit that and regenerate rather
than hand-editing 700 lines of JSON. Grafana's sidecar watches for ConfigMaps
labelled `grafana_dashboard: "1"` and loads them, so the dashboard is created
from source control on install and survives Grafana being deleted.

Default range **1 hour**, refresh **30s** — appropriate for a live demo without
hammering Prometheus.

---

## Installation

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community

kubectl create namespace monitoring

# Grafana admin credentials — generated locally, never committed
kubectl create secret generic pulseguard-grafana-admin -n monitoring \
  --from-literal=admin-user=admin \
  --from-literal=admin-password="$(openssl rand -base64 24)"

helm upgrade --install pulseguard-monitoring \
  prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --version 88.3.0 \
  -f k8s/monitoring/values.yaml \
  --set grafana.admin.existingSecret=pulseguard-grafana-admin \
  --set grafana.admin.userKey=admin-user \
  --set grafana.admin.passwordKey=admin-password \
  --wait --timeout 15m

kubectl apply -f k8s/monitoring/metrics-services.yaml \
              -f k8s/monitoring/service-monitors.yaml \
              -f k8s/monitoring/pulseguard-dashboard-configmap.yaml
```

The version is pinned deliberately. An unpinned install reproduces a different
stack every time it is run, which is the opposite of what a documented
environment is for.

**This is applied by hand, not by Jenkins.** The Task 18 pipeline deploys
application images; its Kubernetes Role is scoped to the `pulseguard` namespace
and deliberately cannot administer `monitoring`. Keeping infrastructure out of
the application pipeline means a dashboard change cannot restart the API.

---

## Opening Grafana

```bash
kubectl port-forward -n monitoring svc/pulseguard-monitoring-grafana 3000:80
```

Then <http://localhost:3000> → **Dashboards** → **PulseGuard Overview**.

Username `admin`; retrieve the password from the Secret:

```bash
kubectl get secret pulseguard-grafana-admin -n monitoring \
  -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

> **Demonstrating from a different network?** `port-forward` goes through the
> Kubernetes API, whose public endpoint is restricted to one address. Update it
> first, or the port-forward will simply hang:
>
> ```bash
> aws eks update-cluster-config --name pulseguard-eks --profile pulseguard \
>   --resources-vpc-config publicAccessCidrs="$(curl -s https://checkip.amazonaws.com)/32"
> ```
>
> Never widen it to `0.0.0.0/0` — that is the Kubernetes API of a cluster
> holding a live database.

---

## Opening Prometheus

Rarely needed — Grafana queries it for you — but useful for checking targets:

```bash
kubectl port-forward -n monitoring svc/pulseguard-monitoring-kube-prometheus 9090:9090
```

Then <http://localhost:9090/targets>. All three PulseGuard backends should be
**UP**.

Prometheus has no Ingress and no load balancer, exactly like Grafana.

---

## Useful queries

```promql
# Is each service being scraped successfully?
up{job=~"control-api|monitor-worker-metrics|notification-service-metrics"}

# Control API request rate by status
sum by (status) (rate(http_server_requests_seconds_count{application="pulseguard-control-api"}[5m]))

# 95th percentile latency
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="pulseguard-control-api"}[5m])))

# Heap per service
sum by (application) (jvm_memory_used_bytes{application=~"pulseguard-.*", area="heap"})

# Monitor checks per minute, split by outcome
sum by (result) (rate(pulseguard_monitor_checks_total[5m]) * 60)

# Emails that will never arrive
increase(pulseguard_notification_delivery_total{status="failed"}[1h])

# Pod restarts
sum by (pod) (kube_pod_container_status_restarts_total{namespace="pulseguard"})
```

---

## Resource footprint

Requests, chosen to fit alongside four application pods on one `t3.medium`:

| Component | CPU | Memory | Memory limit |
| --- | --- | --- | --- |
| Prometheus | 100m | 400Mi | 900Mi |
| Grafana | 50m | 128Mi | 400Mi |
| Operator | 50m | 64Mi | 200Mi |
| kube-state-metrics | 10m | 48Mi | 128Mi |
| node-exporter | 10m | 32Mi | 64Mi |

Node after installation: **61% of CPU and 60% of memory requested**, with no
`MemoryPressure`, `DiskPressure` or `PIDPressure`.

Prometheus has no CPU limit on purpose. Throttling it during a scrape makes it
slow at exactly the moment it has work to do; the memory limit is the control
that actually matters, and it is what stops a runaway series count taking the
node down.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Backend target missing entirely | `serviceMonitorSelectorNilUsesHelmValues: false` not set — Prometheus is ignoring non-chart ServiceMonitors |
| Target DOWN with 404 | The running image predates the Micrometer dependency; check the deployed tag |
| Target DOWN with 401 | Spring Security is not permitting `/actuator/prometheus` |
| Dashboard missing from Grafana | ConfigMap lacks `grafana_dashboard: "1"`, or the sidecar is not watching that namespace |
| Panel says "No data" | The counter has genuinely never incremented — generate the activity |
| Panel shows a query error | Metric name wrong; check the real names at `/actuator/prometheus` |
| `port-forward` hangs | Your IP is not in the EKS API allowlist |
| Prometheus OOMKilled | Retention or series count too high for the memory limit |

Check what a service actually exposes:

```bash
kubectl exec -n pulseguard deployment/control-api -- \
  curl -s http://127.0.0.1:8080/actuator/prometheus | head -40
```

---

## Cost

**No new billable AWS resource was created.** No EC2 instance, no load
balancer, no EBS volume, no public IPv4 address. Prometheus and Grafana run on
the existing node and are reached by port-forward.

What did change is consumption of that node: requests went from 50%/40% to
61%/60%. That is headroom used, not money spent — and it is the reason the
resource requests above are as small as they are.

---

## Cleanup

Monitoring can be removed on its own without touching anything else:

```bash
helm uninstall pulseguard-monitoring -n monitoring
kubectl delete namespace monitoring

# these live in the pulseguard namespace and are not part of the release
kubectl delete -f k8s/monitoring/service-monitors.yaml
kubectl delete -f k8s/monitoring/metrics-services.yaml
```

This deletes **no** application workload, and does not touch RDS, MSK, the ALB,
EKS itself or Jenkins. The applications keep exposing `/actuator/prometheus`;
nothing scrapes it.

---

## Known limitations

- One Prometheus replica, one Grafana replica — no HA
- **Metrics are ephemeral**: no PersistentVolume, so a Prometheus restart loses
  history
- 24-hour retention
- Grafana reached only by `kubectl port-forward`; no public endpoint
- No Alertmanager, therefore no alert notifications of any kind
- No long-term storage, no `remote_write`, no CloudWatch integration
- No OpenTelemetry, no distributed tracing
- No log aggregation — no Loki, no Elasticsearch, no Fluent Bit
- No dedicated Kafka exporter; only what the Spring Kafka clients expose
- No MySQL exporter; database visibility is HikariCP pool metrics only
- No frontend or browser telemetry
- Everything runs on a single `t3.medium` alongside the application
- No scaling, no HPA — by explicit project decision
- Short-lived demonstration environment
