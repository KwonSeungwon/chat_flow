#!/bin/bash

# ChatFlow 로컬 개발 환경 통합 실행 스크립트
# 작성자: KwonSeungwon
# 설명: 전체 ChatFlow 시스템을 로컬에서 한 번에 실행

set -e  # 에러 발생시 스크립트 중단

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
    echo -e "${CYAN}"
    cat << "EOF"
    ╔═══════════════════════════════════════════╗
    ║              ChatFlow 🚀                  ║
    ║         로컬 개발 환경 실행기              ║
    ║                                           ║
    ║  실시간 채팅 + AI 요약 + 한국어 검색      ║
    ╚═══════════════════════════════════════════╝
EOF
    echo -e "${NC}"
}

# 사전 요구사항 체크
check_prerequisites() {
    log_step "사전 요구사항 체크 중..."
    
    local missing_tools=()
    
    # Java 체크
    if ! command -v java &> /dev/null; then
        missing_tools+=("Java 21+")
    else
        java_version=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
        if [ "$java_version" -lt 17 ]; then
            log_warning "Java 버전이 낮습니다. Java 21+ 권장 (현재: $java_version)"
        fi
    fi
    
    # Node.js 체크
    if ! command -v node &> /dev/null; then
        missing_tools+=("Node.js 23.5+")
    else
        node_version=$(node --version | cut -d'v' -f2 | cut -d'.' -f1)
        if [ "$node_version" -lt 18 ]; then
            log_warning "Node.js 버전이 낮습니다. 23.5+ 권장 (현재: v$(node --version))"
        fi
    fi
    
    # Docker 체크
    if ! command -v docker &> /dev/null; then
        missing_tools+=("Docker")
    fi
    
    # Gradle 체크
    if ! command -v ./gradlew &> /dev/null && ! command -v gradle &> /dev/null; then
        missing_tools+=("Gradle")
    fi
    
    if [ ${#missing_tools[@]} -ne 0 ]; then
        log_error "다음 도구들이 필요합니다:"
        for tool in "${missing_tools[@]}"; do
            echo -e "  ${RED}✗${NC} $tool"
        done
        echo ""
        log_info "설치 후 다시 실행해주세요."
        exit 1
    fi
    
    log_success "모든 사전 요구사항이 충족되었습니다!"
}

# 포트 사용 여부 체크
check_ports() {
    log_step "포트 사용 여부 확인 중..."
    
    local ports=(3000 8000 8080 8081 8082 6379 9092 9200 5432 3001 9090 5601)
    local used_ports=()
    
    for port in "${ports[@]}"; do
        if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
            used_ports+=($port)
        fi
    done
    
    if [ ${#used_ports[@]} -ne 0 ]; then
        log_warning "다음 포트들이 이미 사용 중입니다:"
        for port in "${used_ports[@]}"; do
            echo -e "  ${YELLOW}⚠${NC}  $port"
        done
        echo ""
        read -p "계속 진행하시겠습니까? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_info "실행을 중단합니다."
            exit 0
        fi
    else
        log_success "모든 필요 포트가 사용 가능합니다!"
    fi
}

# Gemini API 키 체크
check_gemini_key() {
    log_step "Gemini API 키 확인 중..."
    
    if [ -z "$GEMINI_API_KEY" ]; then
        log_warning "GEMINI_API_KEY 환경변수가 설정되지 않았습니다."
        echo -e "${YELLOW}기본 API 키를 사용하거나 AI 요약 기능이 제한될 수 있습니다.${NC}"
        echo ""
        log_info "AI 요약을 최적으로 사용하려면 다음과 같이 설정하세요:"
        echo -e "${CYAN}export GEMINI_API_KEY=\"AIza-your-gemini-api-key\"${NC}"
        echo ""
        log_info "Google AI Studio에서 API 키 발급: https://aistudio.google.com/app/apikey"
        echo ""
        read -p "기본 설정으로 계속 진행하시겠습니까? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_info "실행을 중단합니다."
            exit 0
        fi
    else
        log_success "Gemini API 키가 설정되어 있습니다!"
    fi
}

# Docker 서비스 시작
start_infrastructure() {
    log_step "인프라 서비스 시작 중..."
    
    log_info "Docker 서비스들을 시작합니다..."
    log_info "  - 🔴 Valkey (Redis 호환)"
    log_info "  - 🟣 Apache Kafka"  
    log_info "  - 🟡 Elasticsearch + Nori"
    log_info "  - 🔵 PostgreSQL"
    log_info "  - 📊 Prometheus & Grafana"
    
    if docker compose up -d valkey kafka elasticsearch postgresql prometheus grafana kibana; then
        log_success "인프라 서비스가 시작되었습니다!"
    else
        log_error "인프라 서비스 시작에 실패했습니다."
        exit 1
    fi
    
    # 서비스 준비 대기
    log_info "서비스 초기화 대기 중..."
    sleep 10
    
    # 상태 체크
    check_service_health "Valkey" "localhost:6379"
    check_service_health "Kafka" "localhost:9092" 
    check_service_health "Elasticsearch" "http://localhost:9200"
}

# 서비스 상태 체크 함수
check_service_health() {
    local service_name=$1
    local endpoint=$2
    local max_attempts=30
    local attempt=1
    
    log_info "$service_name 준비 상태 확인 중..."
    
    while [ $attempt -le $max_attempts ]; do
        case $service_name in
            "Valkey")
                if docker exec valkey valkey-cli ping >/dev/null 2>&1; then
                    log_success "$service_name 준비 완료! ✓"
                    return 0
                fi
                ;;
            "Elasticsearch")
                if curl -s "$endpoint/_cluster/health" >/dev/null 2>&1; then
                    log_success "$service_name 준비 완료! ✓"
                    return 0
                fi
                ;;
            *)
                # 포트 체크
                if nc -z localhost ${endpoint##*:} >/dev/null 2>&1; then
                    log_success "$service_name 준비 완료! ✓"
                    return 0
                fi
                ;;
        esac
        
        echo -n "."
        sleep 2
        ((attempt++))
    done
    
    log_warning "$service_name 응답이 없지만 계속 진행합니다..."
}

# 백엔드 서비스 빌드 및 시작
start_backend() {
    log_step "백엔드 서비스 빌드 및 시작 중..."
    
    # Gradle 빌드
    log_info "프로젝트 빌드 중..."
    if ./gradlew clean build -x test; then
        log_success "빌드 완료!"
    else
        log_error "빌드 실패!"
        exit 1
    fi
    
    # 백엔드 서비스들 백그라운드 시작
    log_info "백엔드 서비스들을 시작합니다..."
    
    # Gateway Service
    log_info "🚪 Gateway Service 시작 중..."
    nohup ./gradlew :gateway-service:bootRun > logs/gateway-service.log 2>&1 &
    echo $! > logs/gateway-service.pid
    
    sleep 5
    
    # Chat Service  
    log_info "💬 Chat Service 시작 중..."
    nohup ./gradlew :chat-service:bootRun > logs/chat-service.log 2>&1 &
    echo $! > logs/chat-service.pid
    
    sleep 5
    
    # AI Summary Service
    log_info "🤖 AI Summary Service 시작 중..."
    nohup ./gradlew :ai-summary-service:bootRun > logs/ai-summary-service.log 2>&1 &
    echo $! > logs/ai-summary-service.pid
    
    sleep 5
    
    # Search Service
    log_info "🔍 Search Service 시작 중..."
    nohup ./gradlew :search-service:bootRun > logs/search-service.log 2>&1 &
    echo $! > logs/search-service.pid
    
    log_success "모든 백엔드 서비스가 시작되었습니다!"
    
    # 서비스 상태 체크
    log_info "백엔드 서비스 상태 확인 중..."
    sleep 10
    
    check_backend_health "Gateway" "http://localhost:8000/actuator/health"
    check_backend_health "Chat" "http://localhost:8080/actuator/health" 
    check_backend_health "AI Summary" "http://localhost:8081/actuator/health"
    check_backend_health "Search" "http://localhost:8082/actuator/health"
}

# 백엔드 서비스 상태 체크
check_backend_health() {
    local service_name=$1
    local health_url=$2
    local max_attempts=20
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        if curl -s "$health_url" | grep -q "UP"; then
            log_success "$service_name Service 준비 완료! ✓"
            return 0
        fi
        echo -n "."
        sleep 3
        ((attempt++))
    done
    
    log_warning "$service_name Service 응답이 없지만 계속 진행합니다..."
}

# 프론트엔드 시작
start_frontend() {
    log_step "프론트엔드 시작 중..."
    
    cd frontend
    
    # 의존성 설치
    if [ ! -d "node_modules" ]; then
        log_info "프론트엔드 의존성 설치 중..."
        if npm install; then
            log_success "의존성 설치 완료!"
        else
            log_error "의존성 설치 실패!"
            exit 1
        fi
    fi
    
    # 개발 서버 시작
    log_info "🎨 Vue3 프론트엔드 시작 중..."
    nohup npm run dev > ../logs/frontend.log 2>&1 &
    echo $! > ../logs/frontend.pid
    
    cd ..
    
    log_success "프론트엔드가 시작되었습니다!"
}

# 로그 디렉토리 생성
create_log_dir() {
    mkdir -p logs
}

# 최종 상태 출력
print_status() {
    echo ""
    echo -e "${GREEN}🎉 ChatFlow가 성공적으로 시작되었습니다! 🎉${NC}"
    echo ""
    echo -e "${WHITE}=== 📊 서비스 접속 정보 ===${NC}"
    echo -e "${CYAN}🌐 웹 애플리케이션${NC}      http://localhost:3000"
    echo -e "${CYAN}🚪 API Gateway${NC}         http://localhost:8000"  
    echo -e "${CYAN}📡 WebSocket${NC}           ws://localhost:8000/ws"
    echo ""
    echo -e "${WHITE}=== 🔧 개발 도구 ===${NC}"
    echo -e "${YELLOW}📈 Grafana${NC}             http://localhost:3001 (admin/admin)"
    echo -e "${YELLOW}📊 Prometheus${NC}          http://localhost:9090"
    echo -e "${YELLOW}🔍 Kibana${NC}              http://localhost:5601"
    echo -e "${YELLOW}📚 Swagger UI${NC}          http://localhost:8000/swagger-ui.html"
    echo ""
    echo -e "${WHITE}=== 🛠️ 인프라 서비스 ===${NC}"
    echo -e "${RED}🔴 Valkey${NC}               localhost:6379"
    echo -e "${PURPLE}🟣 Kafka${NC}                localhost:9092"
    echo -e "${BLUE}🔵 Elasticsearch${NC}        localhost:9200"
    echo -e "${GREEN}🟢 PostgreSQL${NC}           localhost:5432"
    echo ""
    echo -e "${WHITE}=== 📝 로그 확인 ===${NC}"
    echo -e "${CYAN}tail -f logs/gateway-service.log${NC}"
    echo -e "${CYAN}tail -f logs/chat-service.log${NC}"
    echo -e "${CYAN}tail -f logs/ai-summary-service.log${NC}"
    echo -e "${CYAN}tail -f logs/search-service.log${NC}"
    echo -e "${CYAN}tail -f logs/frontend.log${NC}"
    echo ""
    echo -e "${WHITE}=== ⚡ 종료 방법 ===${NC}"
    echo -e "${RED}./stop-chatflow.sh${NC}      - 모든 서비스 종료"
    echo -e "${RED}Ctrl+C${NC}                  - 이 스크립트만 종료 (서비스는 계속 실행)"
    echo ""
}

# 실시간 로그 출력 (선택사항)
follow_logs() {
    echo -e "${YELLOW}실시간 로그를 확인하시겠습니까? (y/N): ${NC}"
    read -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        log_info "실시간 로그 출력 시작... (Ctrl+C로 중단)"
        tail -f logs/*.log
    fi
}

# 메인 실행 함수
main() {
    print_header
    
    create_log_dir
    check_prerequisites
    check_ports
    check_gemini_key
    
    start_infrastructure
    start_backend
    start_frontend
    
    print_status
    
    # 브라우저 자동 열기 (macOS)
    if command -v open &> /dev/null; then
        log_info "브라우저에서 애플리케이션을 여는 중..."
        sleep 3
        open "http://localhost:3000"
    fi
    
    follow_logs
}

# 시그널 핸들러 (Ctrl+C 처리)
cleanup() {
    echo ""
    log_info "스크립트를 종료합니다..."
    log_warning "주의: 서비스들은 백그라운드에서 계속 실행됩니다."
    log_info "모든 서비스를 종료하려면: ./stop-chatflow.sh"
    exit 0
}

trap cleanup SIGINT

# 스크립트 실행
main "$@"