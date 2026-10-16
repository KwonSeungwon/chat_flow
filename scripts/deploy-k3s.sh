#!/usr/bin/env bash
set -euo pipefail

# ChatFlow K3s 배포 스크립트
# 사용법: ./scripts/deploy-k3s.sh [step]
# Steps: infra, secrets, all (= infra + secrets)
#
# 이미지 배포는 GH Actions "Deploy to K3s (manual)" (deploy-k3s.yml)로 한다.
# Helm 배포는 helm/chatflow + values-k3s.yaml 기준으로 수동 실행한다.
#
# 필수 환경 변수 (scripts/.env.deploy 또는 환경에서 주입):
#   DEPLOY_DB_PASSWORD, DEPLOY_JWT_SECRET, DEPLOY_GATEWAY_SECRET
# 이미 존재하는 Secret은 건드리지 않는다 (회전은 kubectl로 직접).

K3S_HOST="node.gossiplog.com"
K3S_USER="ksw"
NAMESPACE="chatflow"
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

scp_k3s() {
  scp -o StrictHostKeyChecking=yes "$@" "$K3S_USER@$K3S_HOST:~/"
}

step_infra() {
  echo "=== Step 1: Deploy Infrastructure ==="
  scp_k3s "$PROJECT_ROOT/k8s/infra/k3s-infra.yaml"
  ssh_k3s "kubectl apply -f ~/k3s-infra.yaml"
  echo "Waiting for infra pods..."
  ssh_k3s "kubectl -n $NAMESPACE wait --for=condition=ready pod -l app=postgresql --timeout=120s 2>/dev/null || true"
  ssh_k3s "kubectl -n $NAMESPACE wait --for=condition=ready pod -l app=valkey --timeout=60s 2>/dev/null || true"
  ssh_k3s "kubectl get pods -n $NAMESPACE"
}

# Secret이 없을 때만 생성한다. 인자: <name> <kubectl create secret generic 인자...>
create_secret_if_absent() {
  local name="$1"; shift
  local out
  if out=$(ssh_k3s "kubectl -n $NAMESPACE get secret $name 2>&1"); then
    echo "  skip: $name (already exists)"
  elif printf '%s' "$out" | grep -q "NotFound"; then
    ssh_k3s "kubectl -n $NAMESPACE create secret generic $name $*"
    echo "  created: $name"
  else
    echo "ERROR: cannot check secret $name (ssh/kubeconfig problem?): $out" >&2
    exit 1
  fi
}

step_secrets() {
  echo "=== Step 2: Create Secrets (skip existing) ==="
  create_secret_if_absent chatflow-postgresql-secret \
    "--from-literal=DB_HOST=postgresql --from-literal=DB_PORT=5432 \
     --from-literal=DB_NAME=chatflow --from-literal=DB_USERNAME=chatflow \
     --from-literal=DB_PASSWORD='$DEPLOY_DB_PASSWORD'"
  create_secret_if_absent chatflow-valkey-secret \
    "--from-literal=VALKEY_HOST=valkey --from-literal=VALKEY_PORT=6379 --from-literal=VALKEY_PASSWORD="
  create_secret_if_absent chatflow-jwt-secret \
    "--from-literal=JWT_SECRET='$DEPLOY_JWT_SECRET'"
  create_secret_if_absent chatflow-gateway-internal-secret \
    "--from-literal=GATEWAY_INTERNAL_SECRET='$DEPLOY_GATEWAY_SECRET'"
  ssh_k3s "kubectl get secrets -n $NAMESPACE"
}

case "${1:-all}" in
  infra)   step_infra ;;
  secrets) step_secrets ;;
  all)
    step_infra
    step_secrets
    ssh_k3s "kubectl get all -n $NAMESPACE"
    ;;
  *) echo "Usage: $0 {infra|secrets|all}" ;;
esac
