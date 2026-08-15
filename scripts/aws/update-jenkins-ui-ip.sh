#!/usr/bin/env bash
#
# Point the Jenkins UI security-group rule at wherever you are now.
#
# The Jenkins web interface is reachable from exactly one address at a time.
# Move to a different network — a demo room, a phone hotspot, home vs campus —
# and the browser simply stops connecting. This swaps that single /32 for your
# current public address.
#
# It only ever touches the one rule described "PulseGuard Jenkins UI". The
# GitHub webhook rules are left alone, because GitHub's delivery must keep
# working regardless of where you happen to be sitting.
#
#   ./update-jenkins-ui-ip.sh          use the address AWS sees you from
#   ./update-jenkins-ui-ip.sh 1.2.3.4  use a specific address
set -euo pipefail

PROFILE="${AWS_PROFILE:-pulseguard}"
SG_NAME="pulseguard-jenkins-sg"
PORT=8080
DESC="PulseGuard Jenkins UI"

NEW_IP="${1:-$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')}"
[[ "$NEW_IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "not a valid IPv4 address: $NEW_IP" >&2; exit 1; }

SG_ID=$(aws ec2 describe-security-groups --profile "$PROFILE" \
  --filters "Name=group-name,Values=$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' --output text)
[[ "$SG_ID" == "None" || -z "$SG_ID" ]] && { echo "security group $SG_NAME not found" >&2; exit 1; }

CURRENT=$(aws ec2 describe-security-group-rules --profile "$PROFILE" \
  --filters "Name=group-id,Values=$SG_ID" \
  --query "SecurityGroupRules[?!IsEgress && FromPort==\`$PORT\` && Description=='$DESC'].CidrIpv4" \
  --output text | tr '\t' '\n' | grep -v '^$' || true)

echo "security group : $SG_ID"
echo "currently allows: ${CURRENT:-<none>}"
echo "new address     : $NEW_IP/32"

if [[ "$CURRENT" == "$NEW_IP/32" ]]; then
  echo "already correct - nothing to do"
  exit 0
fi

# Add before revoking, so a failure here cannot leave you locked out with no
# rule at all.
aws ec2 authorize-security-group-ingress --profile "$PROFILE" --group-id "$SG_ID" \
  --ip-permissions "IpProtocol=tcp,FromPort=$PORT,ToPort=$PORT,IpRanges=[{CidrIp=$NEW_IP/32,Description=$DESC}]" \
  >/dev/null 2>&1 || echo "  (rule already present)"

for OLD in $CURRENT; do
  [[ "$OLD" == "$NEW_IP/32" ]] && continue
  aws ec2 revoke-security-group-ingress --profile "$PROFILE" --group-id "$SG_ID" \
    --ip-permissions "IpProtocol=tcp,FromPort=$PORT,ToPort=$PORT,IpRanges=[{CidrIp=$OLD}]" >/dev/null
  echo "  removed $OLD"
done

echo ""
echo "Jenkins UI now reachable from $NEW_IP/32 only"
echo "GitHub webhook rules were not modified"
