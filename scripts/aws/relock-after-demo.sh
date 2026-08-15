#!/usr/bin/env bash
#
# Close the three endpoints that were opened to 0.0.0.0/0 for a demonstration.
#
# Run this the moment the demo is over. It restores each one to the address you
# are calling from — or to an address you pass in, if you want to lock it to
# somewhere other than where you happen to be sitting.
#
#   ./relock-after-demo.sh              lock to wherever you are now
#   ./relock-after-demo.sh 1.2.3.4      lock to a specific address
#
# GitHub webhook rules are never touched, so deployments keep working.
set -euo pipefail

PROFILE="${AWS_PROFILE:-pulseguard}"
JENKINS_SG="sg-0199c1ebe29d141b3"
CLUSTER="pulseguard-eks"
INGRESS_FILE="k8s/frontend-ingress.yaml"

IP="${1:-$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')}"
[[ "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "not a valid IPv4 address: $IP" >&2; exit 1; }
echo "locking everything to $IP/32"
echo ""

# --- 1. Jenkins UI ----------------------------------------------------------
echo "[1/3] Jenkins UI"
aws ec2 authorize-security-group-ingress --profile "$PROFILE" --group-id "$JENKINS_SG" \
  --ip-permissions "IpProtocol=tcp,FromPort=8080,ToPort=8080,IpRanges=[{CidrIp=$IP/32,Description=PulseGuard Jenkins UI}]" \
  >/dev/null 2>&1 || echo "      (rule already present)"
aws ec2 revoke-security-group-ingress --profile "$PROFILE" --group-id "$JENKINS_SG" \
  --ip-permissions "IpProtocol=tcp,FromPort=8080,ToPort=8080,IpRanges=[{CidrIp=0.0.0.0/0}]" \
  >/dev/null 2>&1 && echo "      closed 0.0.0.0/0" || echo "      (already closed)"

# --- 2. Kubernetes API ------------------------------------------------------
# Do this BEFORE the ALB: the ALB step needs kubectl, and locking the API to an
# address you are not at would strand you.
echo "[2/3] Kubernetes API"
aws eks update-cluster-config --profile "$PROFILE" --name "$CLUSTER" \
  --resources-vpc-config publicAccessCidrs="$IP/32" \
  --query 'update.status' --output text 2>/dev/null \
  && echo "      update submitted — takes a few minutes to apply" \
  || echo "      (already set, or an update is in flight)"

# --- 3. The application load balancer ---------------------------------------
echo "[3/3] Application load balancer"
if [[ -f "$INGRESS_FILE" ]]; then
  # The ALB's inbound rule is generated from this annotation, so the file is the
  # source of truth — editing the security group directly would be undone the
  # next time the controller reconciles.
  if grep -q 'inbound-cidrs: 0.0.0.0/0' "$INGRESS_FILE"; then
    sed -i.bak "s|inbound-cidrs: 0.0.0.0/0|inbound-cidrs: $IP/32|" "$INGRESS_FILE"
    rm -f "${INGRESS_FILE}.bak"
    kubectl apply -f "$INGRESS_FILE" >/dev/null
    echo "      annotation set to $IP/32 and applied"
    echo "      (the controller takes ~30s to update the security group)"
  else
    echo "      (annotation is not 0.0.0.0/0 — leaving it alone)"
  fi
else
  echo "      $INGRESS_FILE not found — run this from the repository root" >&2
fi

echo ""
echo "Done. Verify in a minute with:"
echo "  aws eks describe-cluster --name $CLUSTER --profile $PROFILE \\"
echo "    --query 'cluster.resourcesVpcConfig.publicAccessCidrs'"
