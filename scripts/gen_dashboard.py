#!/usr/bin/env python3
"""Generate the PulseGuard Overview Grafana dashboard as a ConfigMap."""
import json

DS = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
APPS = ["control-api", "monitor-worker", "notification-service"]

panels = []
_id = [0]
def nid():
    _id[0] += 1
    return _id[0]

def gp(x, y, w, h):
    return {"x": x, "y": y, "w": w, "h": h}

def target(expr, legend, instant=False):
    t = {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": "A"}
    if instant:
        t["instant"] = True
    return t

def row(title, y):
    panels.append({"id": nid(), "type": "row", "title": title,
                   "gridPos": gp(0, y, 24, 1), "collapsed": False, "panels": []})

def stat(title, expr, x, y, w=4, h=4, mappings=None, unit="short", color_mode="background",
         thresholds=None, legend=""):
    p = {
        "id": nid(), "type": "stat", "title": title, "datasource": DS,
        "gridPos": gp(x, y, w, h),
        "targets": [target(expr, legend, instant=True)],
        "options": {"colorMode": color_mode, "graphMode": "none",
                    "textMode": "auto", "reduceOptions":
                        {"calcs": ["lastNotNull"], "fields": "", "values": False}},
        "fieldConfig": {"defaults": {"unit": unit, "mappings": mappings or [],
                                     "thresholds": thresholds or
                                     {"mode": "absolute", "steps":
                                      [{"color": "text", "value": None}]}},
                        "overrides": []},
    }
    return panels.append(p)

def ts(title, targets, x, y, w=12, h=8, unit="short", legend_placement="bottom", desc=""):
    panels.append({
        "id": nid(), "type": "timeseries", "title": title, "datasource": DS,
        "description": desc, "gridPos": gp(x, y, w, h),
        "targets": [target(e, l) | {"refId": chr(65 + i)} for i, (e, l) in enumerate(targets)],
        "options": {"legend": {"displayMode": "list", "placement": legend_placement,
                               "showLegend": True},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
        "fieldConfig": {"defaults": {"unit": unit, "custom":
                                     {"lineWidth": 2, "fillOpacity": 8,
                                      "showPoints": "never"}},
                        "overrides": []},
    })

UPDOWN = [{"type": "value", "options": {"0": {"text": "DOWN", "color": "red", "index": 1},
                                        "1": {"text": "UP", "color": "green", "index": 0}}}]
UPTH = {"mode": "absolute", "steps": [{"color": "red", "value": None},
                                      {"color": "green", "value": 1}]}

# ── row 1: is it alive ────────────────────────────────────────────────────
y = 0
row("Application health", y); y += 1
stat("Control API", 'up{job="control-api"}', 0, y, mappings=UPDOWN, thresholds=UPTH)
stat("Monitor Worker", 'up{job="monitor-worker-metrics"}', 4, y, mappings=UPDOWN, thresholds=UPTH)
stat("Notification Service", 'up{job="notification-service-metrics"}', 8, y, mappings=UPDOWN, thresholds=UPTH)
stat("Monitor checks (1h)", 'sum(increase(pulseguard_monitor_checks_total[1h]))', 12, y,
     color_mode="value")
stat("Incidents opened (1h)", 'sum(increase(pulseguard_incidents_opened_total[1h]))', 16, y,
     color_mode="value")
stat("Emails sent (1h)",
     'sum(increase(pulseguard_notification_delivery_total{status="sent"}[1h]))', 20, y,
     color_mode="value")
y += 4

# ── row 2: HTTP ───────────────────────────────────────────────────────────
row("HTTP / API", y); y += 1
ts("Control API request rate",
   [('sum by (status) (rate(http_server_requests_seconds_count{application="pulseguard-control-api"}[5m]))',
     '{{status}}')], 0, y, unit="reqps",
   desc="Requests per second reaching the Control API, split by HTTP status.")
ts("Control API latency (p95 / p50)",
   [('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="pulseguard-control-api"}[5m])))', 'p95'),
    ('histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{application="pulseguard-control-api"}[5m])))', 'p50')],
   12, y, unit="s",
   desc="Request duration. p95 is the number worth watching; p50 shows the typical case.")
y += 8
ts("Error rate (4xx / 5xx)",
   [('sum(rate(http_server_requests_seconds_count{application="pulseguard-control-api",status=~"4.."}[5m]))', '4xx'),
    ('sum(rate(http_server_requests_seconds_count{application="pulseguard-control-api",status=~"5.."}[5m]))', '5xx')],
   0, y, unit="reqps",
   desc="4xx is usually a client sending something wrong — including expected 401s. 5xx is PulseGuard's own fault.")
ts("Database connection pool (HikariCP)",
   [('hikaricp_connections_active{application=~"pulseguard-.*"}', '{{application}} active'),
    ('hikaricp_connections_idle{application=~"pulseguard-.*"}', '{{application}} idle')],
   12, y,
   desc="Active connections climbing toward the pool maximum is the first sign of database pressure.")
y += 8

# ── row 3: JVM ────────────────────────────────────────────────────────────
row("JVM", y); y += 1
ts("Heap used",
   [('sum by (application) (jvm_memory_used_bytes{application=~"pulseguard-.*",area="heap"})',
     '{{application}}')], 0, y, unit="bytes",
   desc="Sawtooth is normal — it is garbage collection doing its job. A rising floor is not.")
ts("Process CPU",
   [('process_cpu_usage{application=~"pulseguard-.*"}', '{{application}}')],
   12, y, unit="percentunit",
   desc="Fraction of one CPU used by each JVM.")
y += 8
ts("GC pause rate",
   [('sum by (application) (rate(jvm_gc_pause_seconds_count{application=~"pulseguard-.*"}[5m]))',
     '{{application}}')], 0, y, unit="ops",
   desc="Garbage collections per second. A sudden climb usually precedes memory trouble.")
ts("Uptime",
   [('process_uptime_seconds{application=~"pulseguard-.*"}', '{{application}}')],
   12, y, unit="s",
   desc="Resets to zero on every restart, which makes restarts obvious.")
y += 8

# ── row 4: monitoring engine ──────────────────────────────────────────────
row("Monitoring engine", y); y += 1
ts("Monitor checks by result",
   [('sum by (result) (rate(pulseguard_monitor_checks_total[5m]) * 60)', '{{result}}')],
   0, y, unit="cpm",
   desc="Checks per minute the worker actually executed and stored, split SUCCESS/FAILURE.")
ts("Outbox publication to Kafka",
   [('sum by (result) (rate(pulseguard_outbox_publish_total[5m]) * 60)', '{{result}}')],
   12, y, unit="cpm",
   desc="Events published to MSK per minute. Sustained failures mean the outbox is filling up.")
y += 8

# ── row 5: incidents and notifications ────────────────────────────────────
row("Incidents and notifications", y); y += 1
ts("Incident lifecycle",
   [('sum(increase(pulseguard_incidents_opened_total[5m]))', 'opened'),
    ('sum(increase(pulseguard_incidents_resolved_total[5m]))', 'resolved')],
   0, y,
   desc="Opened and resolved incidents. In a healthy system these track each other with a lag.")
ts("Email delivery by outcome",
   [('sum by (status) (increase(pulseguard_notification_delivery_total[5m]))', '{{status}}')],
   12, y,
   desc="sent = SMTP accepted it. retrying = will try again. failed = attempts exhausted, nobody gets it.")
y += 8

# ── row 6: kubernetes ─────────────────────────────────────────────────────
row("Kubernetes", y); y += 1
ts("PulseGuard pods ready",
   [('sum by (pod) (kube_pod_status_ready{namespace="pulseguard",condition="true"})', '{{pod}}')],
   0, y,
   desc="1 = ready. A pod dropping to 0 and returning is a restart.")
ts("Pod restarts (total)",
   [('sum by (pod) (kube_pod_container_status_restarts_total{namespace="pulseguard"})', '{{pod}}')],
   12, y,
   desc="Cumulative. A flat line is what you want; any step up deserves a look at the logs.")
y += 8
ts("Pod CPU",
   [('sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="pulseguard",container!=""}[5m]))',
     '{{pod}}')], 0, y, unit="percentunit",
   desc="CPU cores used per pod.")
ts("Pod memory (working set)",
   [('sum by (pod) (container_memory_working_set_bytes{namespace="pulseguard",container!=""})',
     '{{pod}}')], 12, y, unit="bytes",
   desc="What the kernel cannot reclaim. This is the number the OOM killer compares against the limit.")
y += 8
ts("Node CPU",
   [('1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))', 'used')],
   0, y, unit="percentunit",
   desc="Whole-node CPU. One t3.medium runs everything, including Prometheus itself.")
ts("Node memory",
   [('1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)', 'used')],
   12, y, unit="percentunit",
   desc="Whole-node memory in use.")

dashboard = {
    "annotations": {"list": []},
    "editable": True,
    "graphTooltip": 1,
    "title": "PulseGuard Overview",
    "uid": "pulseguard-overview",
    "tags": ["pulseguard"],
    "timezone": "browser",
    "schemaVersion": 39,
    "version": 1,
    "refresh": "30s",
    "time": {"from": "now-1h", "to": "now"},
    "templating": {"list": [{
        "name": "DS_PROMETHEUS", "type": "datasource", "query": "prometheus",
        "label": "Datasource", "hide": 0, "current": {},
        "refresh": 1, "regex": "",
    }]},
    "panels": panels,
}

body = json.dumps(dashboard, indent=2)
indented = "\n".join("    " + l for l in body.split("\n"))

cm = f"""# The PulseGuard Overview dashboard, provisioned rather than hand-built.
#
# kube-prometheus-stack runs a sidecar beside Grafana that watches for
# ConfigMaps labelled `grafana_dashboard: "1"` and loads whatever JSON they
# contain. That means this dashboard is created from source control on install,
# survives Grafana being deleted and recreated, and is reviewable in a diff —
# none of which is true of a dashboard built by clicking in the UI.
#
# Generated by scripts/gen_dashboard.py. Edit that and regenerate rather than
# hand-editing the JSON below.
apiVersion: v1
kind: ConfigMap
metadata:
  name: pulseguard-overview-dashboard
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
    app.kubernetes.io/part-of: pulseguard
data:
  pulseguard-overview.json: |
{indented}
"""
out = "/Users/athifrasheed/Documents/PulseGuard/k8s/monitoring/pulseguard-dashboard-configmap.yaml"
open(out, "w").write(cm)
print(f"wrote {out}")
print(f"panels: {len([p for p in panels if p['type'] != 'row'])}, rows: {len([p for p in panels if p['type'] == 'row'])}")
