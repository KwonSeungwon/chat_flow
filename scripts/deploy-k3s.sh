#!/usr/bin/env bash
set -euo pipefail

# macOS tar: 리소스 포크 파일(._) 제외
export COPYFILE_DISABLE=1

# ChatFlow K3s 배포 스크립트
# 사용법: ./scripts/deploy-k3s.sh [step]
# Steps: infra, secrets, images, helm, all
#
# 필수 환경 변수 (scripts/.env.deploy 또는 환경에서 주입):
#   DEPLOY_DB_PASSWORD, DEPLOY_GEMINI_API_KEY, DEPLOY_JWT_SECRET
# 선택 환경 변수:
#   DEPLOY_GATEWAY_SECRET (미설정 시 자동 생성)

K3S_HOST="node.chatflow.ai.kr"
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
: "${DEPLOY_GEMINI_API_KEY:?DEPLOY_GEMINI_API_KEY 환경 변수를 설정하세요}"
: "${DEPLOY_JWT_SECRET:?DEPLOY_JWT_SECRET 환경 변수를 설정하세요}"
# 게이트웨이 내부 시크릿 (미설정 시 자동 생성)
DEPLOY_GATEWAY_SECRET="${DEPLOY_GATEWAY_SECRET:-$(openssl rand -base64 32)}"

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

step_secrets() {
  echo "=== Step 2: Create Secrets ==="
  # 시크릿 값을 로컬에서 참조하여 SSH heredoc 없이 개별 명령으로 전달
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-postgresql-secret \
    --from-literal=DB_HOST=postgresql --from-literal=DB_PORT=5432 \
    --from-literal=DB_NAME=chatflow --from-literal=DB_USERNAME=chatflow \
    --from-literal=DB_PASSWORD='$DEPLOY_DB_PASSWORD' \
    --dry-run=client -o yaml | kubectl apply -f -"
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-kafka-secret \
    --from-literal=KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    --dry-run=client -o yaml | kubectl apply -f -"
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-valkey-secret \
    --from-literal=VALKEY_HOST=valkey --from-literal=VALKEY_PORT=6379 \
    --from-literal=VALKEY_PASSWORD= \
    --dry-run=client -o yaml | kubectl apply -f -"
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-elasticsearch-secret \
    --from-literal=ELASTICSEARCH_URIS=http://elasticsearch:9200 \
    --from-literal=ELASTICSEARCH_USERNAME= --from-literal=ELASTICSEARCH_PASSWORD= \
    --dry-run=client -o yaml | kubectl apply -f -"
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-gemini-secret \
    --from-literal=GEMINI_API_KEY='$DEPLOY_GEMINI_API_KEY' \
    --dry-run=client -o yaml | kubectl apply -f -"
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-jwt-secret \
    --from-literal=JWT_SECRET='$DEPLOY_JWT_SECRET' \
    --dry-run=client -o yaml | kubectl apply -f -"
  ssh_k3s "kubectl -n $NAMESPACE create secret generic chatflow-gateway-internal-secret \
    --from-literal=GATEWAY_INTERNAL_SECRET='$DEPLOY_GATEWAY_SECRET' \
    --dry-run=client -o yaml | kubectl apply -f -"
  echo "Secrets created."
  ssh_k3s "kubectl get secrets -n $NAMESPACE"
}

step_images() {
  echo "=== Step 3: Build & Transfer Images ==="
  cd "$PROJECT_ROOT"

  # Build all backend services
  ./gradlew bootJar --no-daemon

  # Build Docker images (amd64)
  SERVICES="gateway-service chat-service ai-summary-service search-service"
  for svc in $SERVICES; do
    echo "Building $svc..."
    docker buildx build --platform linux/amd64 --build-arg SERVICE_NAME=$svc \
      -t docker.io/chatflow/$svc:latest --load .
  done

  # Frontend
  echo "Building frontend..."
  cd frontend && flutter build web --release && cd ..
  docker buildx build --platform linux/amd64 -t docker.io/chatflow/frontend:latest --load frontend/

  # Elasticsearch with Nori
  echo "Building elasticsearch..."
  docker buildx build --platform linux/amd64 -t docker.io/chatflow/elasticsearch:latest --load elasticsearch/

  # Save all images
  echo "Saving images..."
  ALL_IMAGES="docker.io/chatflow/gateway-service:latest docker.io/chatflow/chat-service:latest docker.io/chatflow/ai-summary-service:latest docker.io/chatflow/search-service:latest docker.io/chatflow/frontend:latest docker.io/chatflow/elasticsearch:latest"
  docker save $ALL_IMAGES | gzip > /tmp/chatflow-all-images.tar.gz
  echo "Image bundle: $(ls -lh /tmp/chatflow-all-images.tar.gz | awk '{print $5}')"

  # Transfer
  echo "Transferring to K3s..."
  scp_k3s /tmp/chatflow-all-images.tar.gz

  # Import into K3s containerd
  echo "Importing into K3s..."
  ssh_k3s "sudo k3s ctr images import ~/chatflow-all-images.tar.gz"
  echo "Images imported."
  ssh_k3s "sudo k3s ctr images list | grep chatflow"
}

step_helm() {
  echo "=== Step 4: Helm Deploy ==="
  # Copy helm chart to K3s
  cd "$PROJECT_ROOT"
  tar czf /tmp/chatflow-helm.tar.gz -C helm chatflow
  scp_k3s /tmp/chatflow-helm.tar.gz

  ssh_k3s "
    cd ~ && rm -rf chatflow-chart && mkdir chatflow-chart &&
    tar xzf chatflow-helm.tar.gz -C chatflow-chart &&
    cd chatflow-chart/chatflow &&
    helm dependency update . 2>/dev/null || true &&
    helm upgrade --install chatflow . -n $NAMESPACE -f values-k3s.yaml --wait --timeout 5m
  "
  echo "Helm deploy complete."
  ssh_k3s "kubectl get pods -n $NAMESPACE"
}

case "${1:-all}" in
  infra)   step_infra ;;
  secrets) step_secrets ;;
  images)  step_images ;;
  helm)    step_helm ;;
  all)
    step_infra
    step_secrets
    step_images
    step_helm
    echo "=== K3s deployment complete! ==="
    ssh_k3s "kubectl get all -n $NAMESPACE"
    ;;
  *) echo "Usage: $0 {infra|secrets|images|helm|all}" ;;
esac
