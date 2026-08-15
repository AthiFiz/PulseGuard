# PulseGuard Postman collection

`PulseGuard.postman_collection.json` — 109 requests covering registration,
login, authorisation boundaries, projects, members, monitors, checks, incidents
and reporting.

Two environments are provided. **The collection itself is identical for both**;
only `baseUrl` differs.

| Environment | `baseUrl` | Use when |
| --- | --- | --- |
| local | `http://localhost:8080` | Control API running on your machine or in Compose |
| `PulseGuard-AWS.postman_environment.json` | the ALB address | Testing the deployed EKS environment |

## Running against AWS

1. **Import** both files into Postman: the collection and
   `PulseGuard-AWS.postman_environment.json`
2. Select **PulseGuard — AWS (EKS)** in the environment dropdown, top right
3. Run the collection, or individual folders in order

Requests go to the public ALB, whose nginx proxies `/api/` through to the
Control API inside the cluster. Nothing else is exposed: the Monitor Worker and
Notification Service are `ClusterIP` services with no ingress, deliberately, so
there is nothing to point Postman at for those.

> The ALB address changes if the Ingress is deleted and recreated. Check the
> current one with:
> ```bash
> kubectl get ingress -n pulseguard
> ```

## Two requests behave differently against AWS

`0 · Public endpoints` contains two requests that hit `/actuator/...` rather
than `/api/...`:

- **Health** — `GET {{baseUrl}}/actuator/health`
- **Actuator env is NOT public** — `GET {{baseUrl}}/actuator/env`

nginx only proxies `/api/`, so on AWS both fall through to the SPA route and
return **200 with `index.html`** instead of reaching the Control API at all.
The security assertion still holds — the actuator is not publicly routable, it
is simply not reachable through this address — but the test will report a
misleading result.

**Skip those two when running against AWS.** They pass correctly against a local
Control API, where `baseUrl` points straight at port 8080.

To check actuator health on AWS, go through Kubernetes instead of the ALB:

```bash
kubectl exec -n pulseguard deployment/control-api -- \
  curl -s http://127.0.0.1:8080/actuator/health
```

## Order matters

Many requests store values — tokens, project ids, monitor ids — into environment
variables that later requests consume. Running a single request from the middle
of the collection against a fresh environment will usually 401 or 404 because
those variables are still empty.

Run whole folders in order, starting at `1 · Registration`.

## Re-running against a database that already has data

Registration requests expect their email addresses to be unused, so a second run
against the same environment returns `409 Conflict` on those. That is the API
behaving correctly, not a broken test. Either accept the expected conflicts, or
edit the registration emails to fresh values before re-running.
