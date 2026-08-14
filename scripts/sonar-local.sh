#!/usr/bin/env bash
#
# Run coverage and SonarQube analysis for the three PulseGuard backends
# against a local SonarQube instance.
#
# The frontend is deliberately not analysed here — see docs/sonarqube.md. Its
# coverage is still generated with `npm run test:coverage`; only the SonarQube
# step is out of scope.
#
#   docker compose --profile quality up -d sonarqube     # once, and wait for it
#   export SONAR_TOKEN=<a token you generated in the UI>
#   ./scripts/sonar-local.sh
#
# This is a convenience wrapper around commands you can equally well run by
# hand — see docs/sonarqube.md. It is deliberately not a CI pipeline: it
# does not read the Quality Gate, does not enforce a coverage threshold, and
# does not fail the build on Sonar findings. It fails only when a command
# genuinely fails: tests break, or the scanner cannot reach SonarQube.
#
# Analyse one project at a time by passing its name:
#
#   ./scripts/sonar-local.sh control-api

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

: "${SONAR_HOST_URL:=http://localhost:9000}"

if [[ -z "${SONAR_TOKEN:-}" ]]; then
  cat >&2 <<'MSG'
SONAR_TOKEN is not set.

Generate one in SonarQube (http://localhost:9000):
  My Account -> Security -> Generate Tokens -> type "Global Analysis Token"

Then, in this shell:
  export SONAR_TOKEN=<the token>

The token is a credential. Do not commit it or paste it into any tracked file.
MSG
  exit 1
fi

if ! curl -fsS "${SONAR_HOST_URL}/api/system/status" >/dev/null 2>&1; then
  echo "Cannot reach SonarQube at ${SONAR_HOST_URL}." >&2
  echo "Start it with:  docker compose --profile quality up -d sonarqube" >&2
  echo "It takes a few minutes to become ready — check 'docker compose ps'." >&2
  exit 1
fi

echo "SonarQube : ${SONAR_HOST_URL}"
echo "Token     : set (${#SONAR_TOKEN} characters)"
echo

# --- backends -------------------------------------------------------------
# Each is an independent Maven project with its own wrapper, so each is built
# and scanned on its own. Project key and name live in the POM.
scan_backend() {
  local service="$1"
  echo "──────────────────────────────────────────────────────────────"
  echo "  ${service}  —  coverage + analysis"
  echo "──────────────────────────────────────────────────────────────"
  (
    cd "backend/${service}"
    ./mvnw clean verify -Pcoverage
    ./mvnw sonar:sonar \
      -Dsonar.host.url="${SONAR_HOST_URL}" \
      -Dsonar.token="${SONAR_TOKEN}"
  )
  echo
}

TARGET="${1:-all}"
case "$TARGET" in
  control-api|monitor-worker|notification-service) scan_backend "$TARGET" ;;
  all)
    scan_backend control-api
    scan_backend monitor-worker
    scan_backend notification-service
    ;;
  *)
    echo "Unknown target: $TARGET" >&2
    echo "Use one of: control-api, monitor-worker, notification-service, all" >&2
    exit 1
    ;;
esac

echo "Done. Results: ${SONAR_HOST_URL}/projects"
