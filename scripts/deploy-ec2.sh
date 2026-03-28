#!/bin/bash

# ChatFlow EC2 Deployment Script
# Target: Amazon Linux 2023 / Ubuntu on t3.micro or t3.small
#
# Usage:
#   chmod +x scripts/deploy-ec2.sh
#   ./scripts/deploy-ec2.sh

set -euo pipefail

# ── Colors ──────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

log()   { echo -e "${GREEN}[OK]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
info()  { echo -e "${CYAN}[INFO]${NC} $1"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.prod.yml"
ENV_FILE="$PROJECT_ROOT/.env.prod"

# ── Step 1: Check prerequisites ─────────────────

info "ChatFlow EC2 Deployment"
echo ""

# Check .env.prod exists
if [ ! -f "$ENV_FILE" ]; then
    error ".env.prod not found. Run: cp .env.prod.example .env.prod and fill in the values."
fi

# Validate required env vars
source "$ENV_FILE"
[ -z "${GEMINI_API_KEY:-}" ] && error "GEMINI_API_KEY is not set in .env.prod"
[ -z "${DB_PASSWORD:-}" ] && error "DB_PASSWORD is not set in .env.prod"

log "Environment file validated"

# ── Step 2: Install Docker if needed ─────────────

if ! command -v docker &> /dev/null; then
    info "Installing Docker..."
    if command -v apt-get &> /dev/null; then
        # Ubuntu / Debian
        sudo apt-get update -qq
        sudo apt-get install -y -qq docker.io docker-compose-plugin
    elif command -v yum &> /dev/null; then
        # Amazon Linux / RHEL
        sudo yum install -y docker
        sudo systemctl start docker
        sudo systemctl enable docker
        # Install Docker Compose plugin
        sudo mkdir -p /usr/local/lib/docker/cli-plugins
        COMPOSE_VERSION=$(curl -s https://api.github.com/repos/docker/compose/releases/latest | grep '"tag_name"' | head -1 | cut -d'"' -f4)
        sudo curl -SL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-$(uname -m)" \
            -o /usr/local/lib/docker/cli-plugins/docker-compose
        sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    else
        error "Unsupported package manager. Install Docker manually."
    fi
    # Add current user to docker group
    sudo usermod -aG docker "$USER" 2>/dev/null || true
    log "Docker installed"
else
    log "Docker already installed: $(docker --version)"
fi

# Ensure Docker daemon is running
if ! docker info &> /dev/null; then
    sudo systemctl start docker
fi

# ── Step 3: Setup swap (2GB) if needed ───────────

SWAP_SIZE_MB=2048

if [ "$(swapon --show | wc -l)" -le 1 ]; then
    info "Creating ${SWAP_SIZE_MB}MB swap file..."
    sudo fallocate -l ${SWAP_SIZE_MB}M /swapfile 2>/dev/null || sudo dd if=/dev/zero of=/swapfile bs=1M count=$SWAP_SIZE_MB status=progress
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile

    # Persist across reboots
    if ! grep -q '/swapfile' /etc/fstab; then
        echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
    fi

    # Optimize swappiness for low-memory server
    sudo sysctl vm.swappiness=60 > /dev/null
    if ! grep -q 'vm.swappiness' /etc/sysctl.conf; then
        echo 'vm.swappiness=60' | sudo tee -a /etc/sysctl.conf > /dev/null
    fi

    log "Swap configured: ${SWAP_SIZE_MB}MB"
else
    log "Swap already active: $(free -h | awk '/Swap/{print $2}')"
fi

# ── Step 4: Build application ────────────────────

info "Building backend services with Gradle..."
cd "$PROJECT_ROOT"
./gradlew clean build -x test --no-daemon -q

log "Gradle build complete"

# ── Step 5: Deploy with Docker Compose ───────────

info "Building Docker images and starting services..."
cd "$PROJECT_ROOT"

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" build --no-cache
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d

log "All containers started"

# ── Step 6: Health check verification ────────────

wait_for_healthy() {
    local timeout=180  # 3 minutes max (JVM cold start on constrained hardware)
    local interval=5
    local elapsed=0

    declare -A services
    services=(
        ["gateway"]="http://localhost:8000/actuator/health"
        ["frontend"]="http://localhost:80"
    )

    declare -A healthy
    for svc in "${!services[@]}"; do
        healthy[$svc]=false
    done

    local all_count=${#services[@]}
    local healthy_count=0

    while [ $elapsed -lt $timeout ]; do
        for svc in "${!services[@]}"; do
            if [ "${healthy[$svc]}" = "true" ]; then
                continue
            fi

            if curl -sf --max-time 3 "${services[$svc]}" > /dev/null 2>&1; then
                healthy[$svc]=true
                healthy_count=$((healthy_count + 1))
                log "$svc is healthy ($healthy_count/$all_count) [${elapsed}s]"
            fi
        done

        if [ $healthy_count -eq $all_count ]; then
            return 0
        fi

        sleep $interval
        elapsed=$((elapsed + interval))
        printf "\r  Waiting... %ds / %ds (%d/%d healthy)" "$elapsed" "$timeout" "$healthy_count" "$all_count"
    done

    echo ""
    warn "Timed out after ${timeout}s"
    for svc in "${!services[@]}"; do
        if [ "${healthy[$svc]}" = "false" ]; then
            warn "  $svc - NOT healthy (${services[$svc]})"
        fi
    done
    return 1
}

info "Waiting for services to become healthy..."
echo ""

if wait_for_healthy; then
    echo ""
    log "ChatFlow is running!"
    echo ""
    info "Access points:"
    echo "  Frontend:  http://${DOMAIN:-localhost}"
    echo "  Gateway:   http://${DOMAIN:-localhost}:8000"
    echo ""
    info "Useful commands:"
    echo "  docker compose -f $COMPOSE_FILE logs -f          # Follow logs"
    echo "  docker compose -f $COMPOSE_FILE ps               # Service status"
    echo "  docker stats --no-stream                         # Memory usage"
    echo "  docker compose -f $COMPOSE_FILE down             # Stop all"
    echo "  docker compose -f $COMPOSE_FILE up -d --build    # Rebuild & restart"
else
    echo ""
    warn "Some services failed to start. Check logs:"
    echo "  docker compose -f $COMPOSE_FILE logs"
    echo "  docker compose -f $COMPOSE_FILE ps"
    exit 1
fi
