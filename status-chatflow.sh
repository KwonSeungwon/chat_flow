#!/bin/bash

# ChatFlow 서비스 상태 체크 스크립트
# 작성자: KwonSeungwon

# 색상 정의
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m'

print_header() {
    echo -e "${CYAN}"
    cat << "EOF"
    ╔═══════════════════════════════════════════╗
    ║              ChatFlow 📊                  ║
    ║            서비스 상태 체크                ║
    ╚═══════════════════════════════════════════╝
EOF
    echo -e "${NC}"
}

check_service_status() {
    local service_name=$1
    local port=$2
    local health_url=$3
    
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        if [ -n "$health_url" ]; then
            if curl -s "$health_url" >/dev/null 2>&1; then
                echo -e "${GREEN}✅ $service_name${NC} - Port $port (Healthy)"
            else
                echo -e "${YELLOW}⚠️  $service_name${NC} - Port $port (Running but not healthy)"
            fi
        else
            echo -e "${GREEN}✅ $service_name${NC} - Port $port"
        fi
    else
        echo -e "${RED}❌ $service_name${NC} - Port $port (Not running)"
    fi
}

check_docker_service() {
    local container_name=$1
    
    if docker ps --format "table {{.Names}}" | grep -q "^$container_name$" 2>/dev/null; then
        echo -e "${GREEN}✅ $container_name${NC} (Docker)"
    else
        echo -e "${RED}❌ $container_name${NC} (Docker - Not running)"
    fi
}

main() {
    print_header
    
    echo -e "${WHITE}=== 🚀 Application Services ===${NC}"
    check_service_status "Vue3 Frontend     " 3000
    check_service_status "Gateway Service   " 8000 "http://localhost:8000/actuator/health"
    check_service_status "Chat Service      " 8080 "http://localhost:8080/actuator/health"
    check_service_status "AI Summary Service" 8081 "http://localhost:8081/actuator/health"
    check_service_status "Search Service    " 8082 "http://localhost:8082/actuator/health"
    
    echo ""
    echo -e "${WHITE}=== 🐳 Infrastructure Services ===${NC}"
    check_docker_service "valkey"
    check_docker_service "kafka"
    check_docker_service "elasticsearch"
    check_docker_service "postgresql"
    check_docker_service "prometheus"
    check_docker_service "grafana"
    check_docker_service "kibana"
    
    echo ""
    echo -e "${WHITE}=== 🌐 Quick Access ===${NC}"
    echo -e "${CYAN}웹 앱:${NC}      http://localhost:3000"
    echo -e "${CYAN}Grafana:${NC}    http://localhost:3001"
    echo -e "${CYAN}Kibana:${NC}     http://localhost:5601"
    echo ""
}

main "$@"