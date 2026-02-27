#!/bin/bash

# ChatFlow Local Environment Startup Script

set -e

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}🚀 Starting ChatFlow Local Development Environment${NC}"
echo ""

# 환경변수 설정
export SPRING_PROFILES_ACTIVE=local

# Docker services 시작
echo -e "${YELLOW}📦 Starting infrastructure services...${NC}"
docker compose -f docker-compose.local.yml up -d

# 서비스 준비 대기
echo -e "${YELLOW}⏳ Waiting for services to be ready...${NC}"
sleep 15

# 백엔드 서비스들 시작
echo -e "${YELLOW}🔧 Building and starting backend services...${NC}"
./gradlew clean build -x test

echo -e "${YELLOW}🚪 Starting Gateway Service (local)...${NC}"
nohup ./gradlew :gateway-service:bootRun --args="--spring.profiles.active=local" > logs/gateway-local.log 2>&1 &
echo $! > logs/gateway-local.pid
sleep 5

echo -e "${YELLOW}💬 Starting Chat Service (local)...${NC}"
nohup ./gradlew :chat-service:bootRun --args="--spring.profiles.active=local" > logs/chat-local.log 2>&1 &
echo $! > logs/chat-local.pid
sleep 5

echo -e "${YELLOW}🤖 Starting AI Summary Service (local)...${NC}"
nohup ./gradlew :ai-summary-service:bootRun --args="--spring.profiles.active=local" > logs/ai-summary-local.log 2>&1 &
echo $! > logs/ai-summary-local.pid
sleep 5

echo -e "${YELLOW}🔍 Starting Search Service (local)...${NC}"
nohup ./gradlew :search-service:bootRun --args="--spring.profiles.active=local" > logs/search-local.log 2>&1 &
echo $! > logs/search-local.pid
sleep 5

# 프론트엔드 시작
echo -e "${YELLOW}🎨 Starting Frontend...${NC}"
cd frontend
if [ ! -d "node_modules" ]; then
    npm install
fi
nohup npm run dev > ../logs/frontend-local.log 2>&1 &
echo $! > ../logs/frontend-local.pid
cd ..

echo ""
echo -e "${GREEN}✅ ChatFlow Local Environment Started Successfully!${NC}"
echo ""
echo -e "${CYAN}🌐 Access URLs:${NC}"
echo -e "  Frontend:  http://localhost:3000"
echo -e "  API:       http://localhost:8000"
echo -e "  H2 Console: http://localhost:8080/h2-console"
echo -e "  Kibana:    http://localhost:5601"
echo -e "  Grafana:   http://localhost:3001"
echo ""
echo -e "${CYAN}📝 Logs:${NC}"
echo -e "  tail -f logs/*-local.log"
echo ""
echo -e "${CYAN}🛑 Stop:${NC}"
echo -e "  ./scripts/stop-local.sh"
echo ""