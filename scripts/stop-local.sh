#!/bin/bash

# ChatFlow Local Environment Stop Script

set -e

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}🛑 Stopping ChatFlow Local Environment${NC}"
echo ""

# 백엔드 서비스들 종료
echo -e "${YELLOW}🔧 Stopping backend services...${NC}"

services=("frontend-local" "search-local" "ai-summary-local" "chat-local" "gateway-local")
for service in "${services[@]}"; do
    if [ -f "logs/${service}.pid" ]; then
        pid=$(cat "logs/${service}.pid")
        if kill -0 "$pid" 2>/dev/null; then
            echo -e "${YELLOW}Stopping ${service} (PID: $pid)...${NC}"
            kill "$pid"
            sleep 2
            if kill -0 "$pid" 2>/dev/null; then
                echo -e "${RED}Force stopping ${service}...${NC}"
                kill -9 "$pid"
            fi
        fi
        rm -f "logs/${service}.pid"
    fi
done

# Gradle 프로세스 정리
echo -e "${YELLOW}🧹 Cleaning up Gradle processes...${NC}"
./gradlew --stop

# Docker services 중지
echo -e "${YELLOW}📦 Stopping infrastructure services...${NC}"
docker compose -f docker-compose.local.yml down

# 로그 정리 (선택사항)
read -p "$(echo -e ${YELLOW}🗑️ Do you want to clear log files? [y/N]: ${NC})" -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Clearing log files...${NC}"
    rm -f logs/*-local.log
fi

echo ""
echo -e "${GREEN}✅ ChatFlow Local Environment Stopped Successfully!${NC}"
echo ""