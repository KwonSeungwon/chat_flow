#!/usr/bin/env bash
set -euo pipefail

# Gossiplog K3s 배포 스크립트
# 사용법: ./scripts/deploy-k3s.sh [step]
# Steps: infra, secrets, all (= infra + secrets)
#
# 이미지 배포는 GH Actions "Deploy to K3s (manual)" (deploy-k3s.yml)로 한다.
# Helm 배포는 helm/gossiplog + values-k3s.yaml 기준으로 수동 실행한다.
#
# 필수 환경 변수 (scripts/.env.deploy 또는 환경에서 주입):
#   DEPLOY_DB_PASSWORD, DEPLOY_JWT_SECRET, DEPLOY_GATEWAY_SECRET
# 선택: DEPLOY_VALKEY_PASSWORD (없으면 openssl로 무작위 생성)
# 이미 존재하는 Secret은 건드리지 않는다 (회전은 kubectl로 직접).

K3S_HOST="node.gossiplog.com"
K3S_USER="ksw"
NAMESPACE="gossiplog"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# .env.deploy 파일이 있으면 소스 (gitignored)
ENV_FILE="$PROJECT_ROOT/scripts/.env.deploy"
if [ -f "$ENV_FILE" ]; then
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi

# 필수 시크릿 환경 변수 검증
: "${DEPLOY_DB_PASSWORD:?DEPLOY_DB_PASSWORD 환경 변수를 설정하세요 (scripts/.env.deploy 참고)}"
: "${DEPLOY_JWT_SECRET:?DEPLOY_JWT_SECRET 환경 변수를 설정하세요}"
: "${DEPLOY_GATEWAY_SECRET:?DEPLOY_GATEWAY_SECRET 환경 변수를 설정하세요}"

ssh_k3s() {
  ssh -o StrictHostKeyChecking=yes "$K3S_USER@$K3S_HOST" "$@"
}

# 노드에서 kubectl은 sudo + k3s kubeconfig로 호출한다 (~/.kube/config가 없을 수 있음)
kc() {
  ssh_k3s "sudo kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml $*"
}

scp_k3s() {
  scp -o StrictHostKeyChecking=yes "$@" "$K3S_USER@$K3S_HOST:~/"
}

step_infra() {
  echo "=== Step 1: Deploy Infrastructure ==="
  scp_k3s "$PROJECT_ROOT/k8s/infra/k3s-infra.yaml"
  kc "apply -f ~/k3s-infra.yaml"
  echo "Waiting for infra pods..."
  kc "-n $NAMESPACE wait --for=condition=ready pod -l app=postgresql --timeout=120s 2>/dev/null || true"
  kc "-n $NAMESPACE wait --for=condition=ready pod -l app=valkey --timeout=60s 2>/dev/null || true"
  kc "get pods -n $NAMESPACE"
}

# Secret이 없을 때만 생성한다. 인자: <name> <kubectl create secret generic 인자...>
create_secret_if_absent() {
  local name="$1"; shift
  local out
  if out=$(kc "-n $NAMESPACE get secret $name 2>&1"); then
    echo "  skip: $name (already exists)"
  elif printf '%s' "$out" | grep -q "NotFound"; then
    kc "-n $NAMESPACE create secret generic $name $*"
    echo "  created: $name"
  else
    echo "ERROR: cannot check secret $name (ssh/kubeconfig problem?): $out" >&2
    exit 1
  fi
}

step_secrets() {
  echo "=== Step 2: Create Secrets (skip existing) ==="
  create_secret_if_absent gossiplog-postgresql-secret \
    "--from-literal=DB_HOST=postgresql --from-literal=DB_PORT=5432 \
     --from-literal=DB_NAME=chatflow --from-literal=DB_USERNAME=chatflow \
     --from-literal=DB_PASSWORD='$DEPLOY_DB_PASSWORD'"
  # 비면 무작위 생성 (빈 값이면 valkey --requirepass가 기동 실패한다)
  local valkey_password="${DEPLOY_VALKEY_PASSWORD:-$(openssl rand -hex 24)}"
  create_secret_if_absent gossiplog-valkey-secret \
    "--from-literal=VALKEY_HOST=valkey --from-literal=VALKEY_PORT=6379 --from-literal=VALKEY_PASSWORD='$valkey_password'"
  create_secret_if_absent gossiplog-jwt-secret \
    "--from-literal=JWT_SECRET='$DEPLOY_JWT_SECRET'"
  create_secret_if_absent gossiplog-gateway-internal-secret \
    "--from-literal=GATEWAY_INTERNAL_SECRET='$DEPLOY_GATEWAY_SECRET'"
  kc "get secrets -n $NAMESPACE"
}

case "${1:-all}" in
  infra)   step_infra ;;
  secrets) step_secrets ;;
  all)
    step_infra
    step_secrets
    kc "get all -n $NAMESPACE"
    ;;
  *) echo "Usage: $0 {infra|secrets|all}" ;;
esac
