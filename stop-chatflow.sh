#!/bin/bash

# ChatFlow 로컬 개발 환경 종료 스크립트
# 작성자: KwonSeungwon
# 설명: 실행 중인 모든 ChatFlow 서비스를 안전하게 종료

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m' # No Color

# 로그 함수들
log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${PURPLE}[STEP]${NC} $1"
}

# ASCII 아트
print_header() {
    echo -e "${RED}"
    cat << "EOF"
    ╔═══════════════════════════════════════════╗
    ║              ChatFlow 🛑                  ║
    ║           서비스 종료 스크립트             ║
    ║                                           ║
    ║        모든 서비스를 안전하게 종료         ║
    ╚═══════════════════════════════════════════╝
EOF
    echo -e "${NC}"
}

# 백엔드 서비스 종료
stop_backend_services() {
    log_step "백엔드 서비스 종료 중..."
    
    local services=("gateway-service" "chat-service" "ai-summary-service" "search-service")
    
    for service in "${services[@]}"; do
        if [ -f "logs/${service}.pid" ]; then
            local pid=$(cat "logs/${service}.pid")
            if ps -p $pid > /dev/null 2>&1; then
                log_info "🛑 $service 종료 중... (PID: $pid)"
                kill $pid 2>/dev/null || true
                
                # Graceful shutdown 대기
                local count=0
                while ps -p $pid > /dev/null 2>&1 && [ $count -lt 10 ]; do
                    sleep 1
                    ((count++))
                done
                
                # 강제 종료
                if ps -p $pid > /dev/null 2>&1; then
                    log_warning "$service 강제 종료 중..."
                    kill -9 $pid 2>/dev/null || true
                fi
                
                log_success "$service 종료 완료"
            else
                log_warning "$service PID 파일은 있지만 프로세스가 실행 중이 아닙니다."
            fi
            rm -f "logs/${service}.pid"
        else
            log_info "$service PID 파일이 없습니다."
        fi
    done
}

# 프론트엔드 종료
stop_frontend() {
    log_step "프론트엔드 종료 중..."
    
    if [ -f "logs/frontend.pid" ]; then
        local pid=$(cat "logs/frontend.pid")
        if ps -p $pid > /dev/null 2>&1; then
            log_info "🛑 Vue3 프론트엔드 종료 중... (PID: $pid)"
            kill $pid 2>/dev/null || true
            
            # Graceful shutdown 대기
            local count=0
            while ps -p $pid > /dev/null 2>&1 && [ $count -lt 5 ]; do
                sleep 1
                ((count++))
            done
            
            # 강제 종료
            if ps -p $pid > /dev/null 2>&1; then
                log_warning "프론트엔드 강제 종료 중..."
                kill -9 $pid 2>/dev/null || true
            fi
            
            log_success "프론트엔드 종료 완료"
        else
            log_warning "프론트엔드 PID 파일은 있지만 프로세스가 실행 중이 아닙니다."
        fi
        rm -f "logs/frontend.pid"
    else
        log_info "프론트엔드 PID 파일이 없습니다."
    fi
    
    # npm/node 프로세스들 추가 정리
    local npm_pids=$(pgrep -f "npm run dev" 2>/dev/null || true)
    local vite_pids=$(pgrep -f "vite" 2>/dev/null || true)
    local node_pids=$(pgrep -f "node.*vite" 2>/dev/null || true)
    
    for pid in $npm_pids $vite_pids $node_pids; do
        if [ -n "$pid" ]; then
            log_info "추가 프론트엔드 프로세스 종료 중... (PID: $pid)"
            kill $pid 2>/dev/null || true
        fi
    done
}

# Gradle 데몬 종료
stop_gradle_daemon() {
    log_step "Gradle 데몬 종료 중..."
    
    if command -v ./gradlew &> /dev/null; then
        ./gradlew --stop >/dev/null 2>&1 || true
        log_success "Gradle 데몬 종료 완료"
    elif command -v gradle &> /dev/null; then
        gradle --stop >/dev/null 2>&1 || true
        log_success "Gradle 데몬 종료 완료"
    else
        log_info "Gradle을 찾을 수 없습니다."
    fi
}

# Docker 서비스 종료
stop_docker_services() {
    log_step "Docker 인프라 서비스 종료 중..."
    
    if command -v docker &> /dev/null; then
        # Docker Compose 서비스들 종료
        log_info "Docker Compose 서비스 종료 중..."
        docker compose down 2>/dev/null || docker-compose down 2>/dev/null || true
        
        # 개별 컨테이너들 확인 및 정리
        local containers=$(docker ps -q --filter "name=valkey" --filter "name=kafka" --filter "name=elasticsearch" --filter "name=postgresql" --filter "name=prometheus" --filter "name=grafana" --filter "name=kibana" 2>/dev/null || true)
        
        if [ -n "$containers" ]; then
            log_info "남은 ChatFlow 컨테이너들 정리 중..."
            echo "$containers" | xargs docker stop 2>/dev/null || true
            echo "$containers" | xargs docker rm 2>/dev/null || true
        fi
        
        log_success "Docker 서비스 종료 완료"
    else
        log_warning "Docker를 찾을 수 없습니다."
    fi
}

# 포트 기반 프로세스 정리
cleanup_by_ports() {
    log_step "포트 기반 프로세스 정리 중..."
    
    local ports=(3000 8000 8080 8081 8082)
    
    for port in "${ports[@]}"; do
        local pids=$(lsof -ti:$port 2>/dev/null || true)
        if [ -n "$pids" ]; then
            log_info "포트 $port 사용 프로세스 종료 중..."
            echo "$pids" | xargs kill -9 2>/dev/null || true
        fi
    done
    
    log_success "포트 기반 정리 완료"
}

# 로그 파일 정리 (선택사항)
cleanup_logs() {
    echo ""
    echo -e "${YELLOW}로그 파일을 정리하시겠습니까? (y/N): ${NC}"
    read -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        log_info "로그 파일 정리 중..."
        rm -f logs/*.log logs/*.pid
        log_success "로그 파일 정리 완료"
    fi
}

# 종료 확인
confirm_shutdown() {
    echo -e "${YELLOW}모든 ChatFlow 서비스를 종료하시겠습니까? (y/N): ${NC}"
    read -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log_info "종료를 취소합니다."
        exit 0
    fi
}

# 최종 상태 확인
check_final_status() {
    log_step "최종 상태 확인 중..."
    
    local remaining_processes=0
    
    # Java 프로세스 체크
    local java_procs=$(pgrep -f "java.*chatflow" 2>/dev/null || true)
    if [ -n "$java_procs" ]; then
        log_warning "남은 Java 프로세스가 있습니다:"
        echo "$java_procs" | while read pid; do
            echo -e "  ${YELLOW}PID $pid:${NC} $(ps -p $pid -o comm= 2>/dev/null || echo 'Unknown')"
        done
        remaining_processes=1
    fi
    
    # Node.js 프로세스 체크
    local node_procs=$(pgrep -f "node.*chatflow\|npm run dev\|vite" 2>/dev/null || true)
    if [ -n "$node_procs" ]; then
        log_warning "남은 Node.js 프로세스가 있습니다:"
        echo "$node_procs" | while read pid; do
            echo -e "  ${YELLOW}PID $pid:${NC} $(ps -p $pid -o comm= 2>/dev/null || echo 'Unknown')"
        done
        remaining_processes=1
    fi
    
    # 포트 사용 체크
    local ports=(3000 8000 8080 8081 8082)
    for port in "${ports[@]}"; do
        if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
            log_warning "포트 $port이 여전히 사용 중입니다."
            remaining_processes=1
        fi
    done
    
    if [ $remaining_processes -eq 0 ]; then
        log_success "모든 ChatFlow 서비스가 완전히 종료되었습니다! ✅"
    else
        echo ""
        log_warning "일부 프로세스가 남아있을 수 있습니다."
        echo -e "${CYAN}수동으로 정리하려면:${NC}"
        echo -e "${CYAN}  pkill -f chatflow${NC}"
        echo -e "${CYAN}  pkill -f 'gradle.*bootRun'${NC}"
        echo -e "${CYAN}  pkill -f 'npm run dev'${NC}"
    fi
}

# 종료 완료 메시지
print_completion() {
    echo ""
    echo -e "${GREEN}🏁 ChatFlow 종료 완료! 🏁${NC}"
    echo ""
    echo -e "${WHITE}=== 📊 종료된 서비스 ===${NC}"
    echo -e "${CYAN}🚪 Gateway Service${NC}        (Port: 8000)"
    echo -e "${CYAN}💬 Chat Service${NC}           (Port: 8080)"  
    echo -e "${CYAN}🤖 AI Summary Service${NC}     (Port: 8081)"
    echo -e "${CYAN}🔍 Search Service${NC}         (Port: 8082)"
    echo -e "${CYAN}🎨 Vue3 Frontend${NC}          (Port: 3000)"
    echo ""
    echo -e "${WHITE}=== 🐳 종료된 인프라 ===${NC}"
    echo -e "${RED}🔴 Valkey${NC}                 (Port: 6379)"
    echo -e "${PURPLE}🟣 Kafka + Zookeeper${NC}      (Port: 9092, 2181)"
    echo -e "${BLUE}🔵 Elasticsearch + Kibana${NC}  (Port: 9200, 5601)"
    echo -e "${GREEN}🟢 PostgreSQL${NC}             (Port: 5432)"
    echo -e "${YELLOW}📊 Prometheus + Grafana${NC}    (Port: 9090, 3001)"
    echo ""
    echo -e "${WHITE}다시 시작하려면: ${GREEN}./start-chatflow.sh${NC}"
    echo ""
}

# 메인 실행 함수
main() {
    print_header
    
    # 강제 종료 모드 체크
    if [ "$1" = "--force" ] || [ "$1" = "-f" ]; then
        log_warning "강제 종료 모드로 실행합니다."
    else
        confirm_shutdown
    fi
    
    stop_frontend
    stop_backend_services
    stop_gradle_daemon
    cleanup_by_ports
    stop_docker_services
    
    check_final_status
    
    cleanup_logs
    
    print_completion
}

# 도움말
show_help() {
    echo "ChatFlow 종료 스크립트"
    echo ""
    echo "사용법:"
    echo "  ./stop-chatflow.sh          일반 종료 (확인 메시지)"
    echo "  ./stop-chatflow.sh --force  강제 종료 (확인 없이)"
    echo "  ./stop-chatflow.sh -f       강제 종료 (축약형)"
    echo "  ./stop-chatflow.sh --help   도움말 표시"
    echo ""
}

# 인자 처리
case "${1:-}" in
    --help|-h)
        show_help
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac