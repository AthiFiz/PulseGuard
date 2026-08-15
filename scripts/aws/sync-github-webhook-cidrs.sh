#!/usr/bin/env bash
#
# Keep the Jenkins security group's GitHub-webhook rules in step with GitHub's
# published hook ranges.
#
# GitHub changes these ranges occasionally and without warning. Hardcoding them
# means webhook deliveries start failing one day for no visible reason, so they
# are fetched from the Meta API every run instead:
#
#     https://api.github.com/meta  →  .hooks[]
#
# Only rules described exactly "GitHub webhook" are touched. The browser-access
# rule ("PulseGuard Jenkins UI") is never read or modified by this script —
# changing your Wi-Fi must not disturb webhook delivery, and vice versa.
#
# Uses python3 rather than jq: python3 ships with macOS and Amazon Linux, jq
# does not, and one fewer install is one fewer thing to go wrong on a demo day.
#
#   ./sync-github-webhook-cidrs.sh            apply changes
#   ./sync-github-webhook-cidrs.sh --dry-run  show what would change
set -euo pipefail

PROFILE="${AWS_PROFILE:-pulseguard}"
SG_NAME="pulseguard-jenkins-sg"
PORT=8080
DESC="GitHub webhook"
DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

SG_ID=$(aws ec2 describe-security-groups --profile "$PROFILE" \
  --filters "Name=group-name,Values=$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' --output text)
[[ "$SG_ID" == "None" || -z "$SG_ID" ]] && { echo "security group $SG_NAME not found" >&2; exit 1; }
echo "security group : $SG_ID"

# --- what GitHub says today ------------------------------------------------
# IPv4 only: the rule below is an IpRanges rule and mixing v6 in would fail.
WANT=$(curl -fsS https://api.github.com/meta | python3 -c '
import sys, json
for c in json.load(sys.stdin).get("hooks", []):
    if ":" not in c:
        print(c)
' | sort -u)
[[ -z "$WANT" ]] && { echo "GitHub Meta API returned no IPv4 hook ranges - refusing to continue" >&2; exit 1; }
echo "github ranges  : $(grep -c . <<<"$WANT")"

# --- what the security group has now ---------------------------------------
HAVE=$(aws ec2 describe-security-group-rules --profile "$PROFILE" \
  --filters "Name=group-id,Values=$SG_ID" \
  --query "SecurityGroupRules[?!IsEgress && FromPort==\`$PORT\` && Description=='$DESC'].CidrIpv4" \
  --output text | tr '\t' '\n' | grep -v '^$' | sort -u || true)
echo "existing rules : $(grep -c . <<<"${HAVE:-}" || echo 0)"

ADD=$(comm -23 <(echo "$WANT") <(echo "${HAVE:-}") || true)
DEL=$(comm -13 <(echo "$WANT") <(echo "${HAVE:-}") || true)

[[ -z "$ADD" && -z "$DEL" ]] && { echo "already in sync"; exit 0; }
[[ -n "$ADD" ]] && echo "to add    : $(grep -c . <<<"$ADD")"
[[ -n "$DEL" ]] && echo "to remove : $(grep -c . <<<"$DEL")"

if $DRY_RUN; then
  echo "(dry run - nothing changed)"
  exit 0
fi

build_perms() {   # $1 = newline-separated CIDRs, $2 = description or empty
  python3 -c '
import sys, json
cidrs = [l.strip() for l in sys.stdin if l.strip()]
desc, port = sys.argv[1], int(sys.argv[2])
rng = [({"CidrIp": c, "Description": desc} if desc else {"CidrIp": c}) for c in cidrs]
print(json.dumps([{"IpProtocol": "tcp", "FromPort": port, "ToPort": port, "IpRanges": rng}]))
' "$2" "$PORT"
}

if [[ -n "$ADD" ]]; then
  aws ec2 authorize-security-group-ingress --profile "$PROFILE" --group-id "$SG_ID" \
    --ip-permissions "$(build_perms "$ADD" "$DESC" <<<"$ADD")" >/dev/null
  echo "added $(grep -c . <<<"$ADD") range(s)"
fi

if [[ -n "$DEL" ]]; then
  aws ec2 revoke-security-group-ingress --profile "$PROFILE" --group-id "$SG_ID" \
    --ip-permissions "$(build_perms "$DEL" "" <<<"$DEL")" >/dev/null
  echo "removed $(grep -c . <<<"$DEL") stale range(s)"
fi

echo "done - the Jenkins UI rule was not touched"
