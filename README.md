# ChatFlow - 실시간 채팅 + AI 요약 시스템

실시간 채팅과 AI 기반 대화 요약 기능을 제공하는 마이크로서비스 아키텍처 기반 시스템입니다.

## 🏗️ 아키텍처

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  Vue3 Frontend  │    │  Gateway        │    │  Chat Service   │
│  (Port: 3000)   │◄──►│  (Port: 8000)   │◄──►│  (Port: 8080)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                              │                         │
                              │                         ▼
┌─────────────────┐           │                ┌─────────────────┐
│ AI Summary      │◄──────────┘                │     Kafka       │
│ Service         │                            │   (Port: 9092)  │
│ (Port: 8081)    │                            └─────────────────┘
└─────────────────┘                                     │
                                                        ▼
┌─────────────────┐           ┌─────────────────┐    ┌─────────────────┐
│ Search Service  │◄──────────│   Elasticsearch │    │     Redis       │
│ (Port: 8082)    │           │   (Port: 9200)  │    │   (Port: 6379)  │
└─────────────────┘           └─────────────────┘    └─────────────────┘
```

## 🚀 기술 스택

### Frontend
- **Vue 3.4** + **TypeScript** + **Vite**
- **Bootstrap 5** - UI 프레임워크
- **Pinia** - 상태 관리
- **STOMP.js** - WebSocket 통신
- **Node.js 23.5** - 런타임
- **Electron 28** - 데스크톱 애플리케이션

### Backend
- **Java 21** + **Spring Boot 3.2.0**
- **Spring WebSocket** - 실시간 통신
- **Apache Kafka** - 메시지 큐
- **Redis** - 캐싱 및 세션 관리
- **Elasticsearch** - 검색 엔진
- **LangChain4J** - AI 통합

### DevOps & 모니터링
- **Docker & Docker Compose**
- **Kubernetes**
- **Prometheus & Grafana** - 모니터링
- **Gradle** - 빌드 도구

## 📦 모듈 구조

```
chat_flow/
├── frontend/              # Vue3 프론트엔드
│   ├── src/
│   │   ├── components/    # 재사용 컴포넌트
│   │   ├── views/         # 페이지 뷰
│   │   ├── composables/   # Vue Composables
│   │   ├── types/         # TypeScript 타입
│   │   └── utils/         # 유틸리티 함수
│   ├── Dockerfile
│   └── package.json
├── common/                # 공통 모듈
├── chat-service/          # 실시간 채팅 서비스
├── ai-summary-service/    # AI 요약 서비스
├── search-service/        # 검색 서비스
├── gateway-service/       # API 게이트웨이
├── k8s/                   # Kubernetes 설정
├── monitoring/            # 모니터링 설정
└── docker-compose.yml     # 개발 환경 설정
```

## 🏃‍♂️ 실행 방법

### 개발 환경 실행

#### 1. 인프라 서비스 실행
```bash
docker-compose up -d kafka redis elasticsearch postgresql prometheus
```

#### 2. 백엔드 서비스 빌드 및 실행
```bash
# 애플리케이션 빌드
./gradlew build

# Gateway Service
./gradlew :gateway-service:bootRun

# Chat Service  
./gradlew :chat-service:bootRun

# AI Summary Service
./gradlew :ai-summary-service:bootRun

# Search Service
./gradlew :search-service:bootRun
```

#### 3. 프론트엔드 실행

##### 웹 버전
```bash
cd frontend

# Node.js 23.5 필요
npm install
npm run dev
```

##### 데스크톱 앱 (Electron)
```bash
cd frontend

# 개발 모드
npm run electron:dev

# 프로덕션 빌드
npm run electron:dist
```

### 프로덕션 환경 (Docker)

#### 전체 스택 실행
```bash
docker-compose up -d
```

#### 개별 서비스 빌드
```bash
# Frontend
cd frontend && docker build -t chatflow/frontend .

# Backend services
docker build --build-arg SERVICE_NAME=chat-service -t chatflow/chat-service .
docker build --build-arg SERVICE_NAME=ai-summary-service -t chatflow/ai-summary-service .
docker build --build-arg SERVICE_NAME=search-service -t chatflow/search-service .
docker build --build-arg SERVICE_NAME=gateway-service -t chatflow/gateway-service .
```

## 🔧 환경 설정

### 필수 환경 변수
```bash
# AI Summary Service
export OPENAI_API_KEY=your-openai-api-key
```

## 📊 접속 URL

### 애플리케이션
- **웹 앱**: http://localhost:3000
- **데스크톱 앱**: Electron 실행 후 자동 열림
- **API Gateway**: http://localhost:8000

### 개별 서비스
- **Chat Service**: http://localhost:8080
- **AI Summary Service**: http://localhost:8081  
- **Search Service**: http://localhost:8082

### 모니터링 & 도구
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3001 (admin/admin)
- **Elasticsearch**: http://localhost:9200
- **Kibana**: http://localhost:5601

## 🎯 주요 기능

### 1. 크로스 플랫폼 인터페이스
- **웹 앱**: Vue 3 + Bootstrap 5 반응형 디자인
- **데스크톱 앱**: Electron으로 Windows/macOS/Linux 지원
- **OS 테마 연동**: 시스템 다크/라이트 모드 자동 감지
- **시스템 트레이**: 백그라운드 실행 및 알림
- **네이티브 메뉴**: 플랫폼별 최적화된 메뉴

### 2. 실시간 채팅
- **WebSocket** 기반 실시간 메시지 전송
- **다중 채팅방** 지원
- **사용자 입장/퇴장** 알림
- **온라인 사용자** 표시

### 3. AI 요약
- **LangChain4J + OpenAI GPT-4** 연동
- **자동 대화 요약** 생성
- **Kafka** 를 통한 비동기 처리
- **실시간 요약 표시**

### 4. 고급 검색
- **Elasticsearch** 기반 전문 검색
- **키워드, 사용자명, 시간대별** 검색
- **검색 결과 하이라이트**
- **페이지네이션** 지원

### 5. 마이크로서비스 아키텍처
- **API Gateway** 라우팅
- **Circuit Breaker** 패턴
- **서비스 간 통신** (Kafka)
- **독립적 배포** 가능

## 🌐 API 엔드포인트

### Gateway (Port: 8000)
- WebSocket: `ws://localhost:8000/ws`
- REST API: `http://localhost:8000/api/{service}/**`

### 개별 서비스
- Chat Service: `http://localhost:8080`
- AI Summary Service: `http://localhost:8081`
- Search Service: `http://localhost:8082`

## 🚢 배포

### Docker 빌드
```bash
# 개별 서비스 빌드
docker build --build-arg SERVICE_NAME=chat-service -t chatflow/chat-service .
docker build --build-arg SERVICE_NAME=ai-summary-service -t chatflow/ai-summary-service .
docker build --build-arg SERVICE_NAME=search-service -t chatflow/search-service .
docker build --build-arg SERVICE_NAME=gateway-service -t chatflow/gateway-service .
```

### Kubernetes 배포
```bash
kubectl apply -f k8s/
```

## 🔄 개발 워크플로우

1. **메시지 전송**: 사용자가 채팅 메시지 전송
2. **실시간 브로드캐스트**: WebSocket으로 실시간 전파
3. **Kafka 발행**: 메시지를 Kafka 토픽으로 발행
4. **AI 요약**: 특정 조건 만족 시 AI 요약 생성
5. **검색 인덱싱**: Elasticsearch에 메시지 인덱싱
6. **모니터링**: Prometheus/Grafana로 메트릭 수집

## 🧪 테스트

```bash
# 전체 테스트 실행
./gradlew test

# 특정 모듈 테스트
./gradlew :chat-service:test
```

## 📝 TODO

- [ ] 사용자 인증/인가 시스템
- [ ] 파일 업로드 기능
- [ ] 메시지 읽음 처리
- [ ] 푸시 알림
- [ ] 다국어 지원