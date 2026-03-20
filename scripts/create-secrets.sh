#!/usr/bin/env bash
set -euo pipefail

# ChatFlow K8s Secret 생성 스크립트
# 사용법: ./scripts/create-secrets.sh [namespace]
#
# 환경변수를 설정한 후 실행하세요:
#   export DB_HOST=your-db-host
#   export GEMINI_API_KEY=your-key
#   ./scripts/create-secrets.sh chatflow

NAMESPACE="${1:-chatflow}"

echo "=== ChatFlow Secret 생성 (namespace: ${NAMESPACE}) ==="

# 네임스페이스 생성 (없으면)
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

# PostgreSQL
kubectl create secret generic chatflow-postgresql-secret \
  --namespace="${NAMESPACE}" \
  --from-literal=DB_HOST="${DB_HOST:-localhost}" \
  --from-literal=DB_PORT="${DB_PORT:-5432}" \
  --from-literal=DB_NAME="${DB_NAME:-chatflow}" \
  --from-literal=DB_USERNAME="${DB_USERNAME:-chatflow}" \
  --from-literal=DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[OK] chatflow-postgresql-secret"

# Kafka
kubectl create secret generic chatflow-kafka-secret \
  --namespace="${NAMESPACE}" \
  --from-literal=KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[OK] chatflow-kafka-secret"

# Valkey (Redis)
kubectl create secret generic chatflow-valkey-secret \
  --namespace="${NAMESPACE}" \
  --from-literal=VALKEY_HOST="${VALKEY_HOST:-localhost}" \
  --from-literal=VALKEY_PORT="${VALKEY_PORT:-6379}" \
  --from-literal=VALKEY_PASSWORD="${VALKEY_PASSWORD:-}" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[OK] chatflow-valkey-secret"

# Elasticsearch
kubectl create secret generic chatflow-elasticsearch-secret \
  --namespace="${NAMESPACE}" \
  --from-literal=ELASTICSEARCH_URIS="${ELASTICSEARCH_URIS:-http://localhost:9200}" \
  --from-literal=ELASTICSEARCH_USERNAME="${ELASTICSEARCH_USERNAME:-}" \
  --from-literal=ELASTICSEARCH_PASSWORD="${ELASTICSEARCH_PASSWORD:-}" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[OK] chatflow-elasticsearch-secret"

# Gemini API
kubectl create secret generic chatflow-gemini-secret \
  --namespace="${NAMESPACE}" \
  --from-literal=GEMINI_API_KEY="${GEMINI_API_KEY:?GEMINI_API_KEY is required}" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[OK] chatflow-gemini-secret"

# GHCR Pull Secret (Docker registry 인증)
if [ -n "${GHCR_TOKEN:-}" ]; then
  kubectl create secret docker-registry ghcr-pull-secret \
    --namespace="${NAMESPACE}" \
    --docker-server=ghcr.io \
    --docker-username="${GHCR_USERNAME:-$GITHUB_ACTOR}" \
    --docker-password="${GHCR_TOKEN}" \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "[OK] ghcr-pull-secret"
else
  echo "[SKIP] ghcr-pull-secret (GHCR_TOKEN not set)"
fi

echo ""
echo "=== 완료! Secret 확인: ==="
kubectl get secrets -n "${NAMESPACE}" | grep chatflow
