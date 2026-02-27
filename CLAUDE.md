# CLAUDE.md - ChatFlow Project Guide

## Project Overview

ChatFlow는 마이크로서비스 아키텍처 기반의 실시간 채팅 플랫폼이다. AI 대화 요약(Google Gemini), 한국어 전문 검색(Elasticsearch + Nori), WebSocket 실시간 통신을 핵심 기능으로 제공한다.

## Architecture

```
Frontend (Vue 3, Port 3000)
    ↓ HTTP / WebSocket
Gateway Service (Spring Cloud Gateway, Port 8000)
    ├→ Chat Service (Port 8080) - 실시간 채팅, WebSocket/STOMP
    ├→ AI Summary Service (Port 8081) - LangChain4J + Gemini 요약
    └→ Search Service (Port 8082) - Elasticsearch 한국어 검색

비동기 통신 (Kafka Topics):
    chat-messages: Chat → Search, AI Summary
    ai-summary-requests: Chat → AI Summary
    ai-summaries: AI Summary → Search
```

## Tech Stack

- **Backend**: Java 17+, Spring Boot 3.2, Spring Cloud Gateway, Spring WebSocket (STOMP)
- **Frontend**: Vue 3.4, TypeScript 5.5, Vite 5.4, Electron 31, Bootstrap 5, Pinia
- **Data**: PostgreSQL 16 (prod) / H2 (local), Valkey 7.2 (Redis 호환), Elasticsearch 8.11 + Nori
- **Messaging**: Apache Kafka 7.4
- **AI**: LangChain4J 0.25 + Google Gemini 1.5 Flash
- **Build**: Gradle 8.5 (backend), npm (frontend)
- **Monitoring**: Prometheus, Grafana, Kibana

## Project Structure

```
chat_flow/
├── common/                  # 공유 라이브러리 (BaseMessage, ChatMessage, ApiResponse)
├── chat-service/            # 실시간 채팅 (WebSocket + Kafka + JPA 영속화)
│   ├── entity/              # ChatRoom, ChatMessageEntity
│   ├── repository/          # ChatRoomRepository, ChatMessageRepository
│   ├── controller/          # ChatController (WS), ChatRoomController (REST)
│   └── service/             # ChatService
├── ai-summary-service/      # AI 요약 (Kafka Consumer → Gemini → REST API)
│   ├── controller/          # AiSummaryController
│   └── service/             # AiSummaryService
├── search-service/          # 검색 (Kafka Consumer → Elasticsearch 인덱싱 + REST API)
├── gateway-service/         # API Gateway (라우팅, CORS, Circuit Breaker)
├── frontend/                # Vue 3 + Electron 프론트엔드
│   ├── src/components/      # Vue 컴포넌트
│   ├── src/composables/     # useWebSocket, useTheme, useElectron
│   ├── src/views/           # ChatView, SearchView
│   └── electron/            # Electron 메인 프로세스
├── elasticsearch/           # 커스텀 Dockerfile (Nori 플러그인)
├── monitoring/              # Prometheus 설정
├── k8s/                     # Kubernetes 매니페스트
└── scripts/                 # 배포 스크립트
```

## Build & Run Commands

### Infrastructure (Docker)

```bash
# 로컬 인프라 시작 (Valkey, Kafka, Elasticsearch, PostgreSQL, etc.)
docker compose -f docker-compose.local.yml up -d

# 인프라 중지
docker compose -f docker-compose.local.yml down
```

### Backend (Gradle)

```bash
# 전체 빌드
./gradlew build

# 전체 서비스 병렬 실행
./gradlew bootRun --parallel

# 개별 서비스 실행
./gradlew :gateway-service:bootRun
./gradlew :chat-service:bootRun
./gradlew :ai-summary-service:bootRun
./gradlew :search-service:bootRun

# 테스트
./gradlew test
./gradlew :chat-service:test          # 개별 서비스
./gradlew jacocoTestReport            # 커버리지 리포트
```

### Frontend (npm)

```bash
cd frontend

npm install            # 의존성 설치
npm run dev            # 개발 서버 (Port 3000)
npm run build          # 프로덕션 빌드
npm run lint           # ESLint 검사
npm run type-check     # TypeScript 타입 체크

# Electron (데스크톱)
npm run electron:dev   # 개발 모드
npm run electron:dist  # 프로덕션 빌드
```

## Key Patterns & Conventions

### Backend

- **Gradle 멀티 모듈**: `settings.gradle`에 5개 서브프로젝트 정의, `common`은 공유 라이브러리
- **공통 모듈**: `common`은 `bootJar` 비활성화, 다른 서비스에서 `implementation project(':common')`로 참조
- **프로필 기반 설정**: `application-local.yml` (개발), `application-prod.yml` (운영)
- **Kafka 메시지**: JSON 직렬화, `ChatMessage` DTO 기반, chatRoomId를 파티션 키로 사용
- **WebSocket**: STOMP 프로토콜, `/app/chat.sendMessage` (전송), `/topic/chat/{roomId}` (구독)
- **패키지 구조**: `com.chatflow.{서비스명}` (예: `com.chatflow.chat`, `com.chatflow.search`)
- **Lombok**: 전체 서비스에서 `@Data`, `@Slf4j` 등 사용

### Frontend

- **Composition API**: `<script setup lang="ts">` 패턴 사용
- **Composables**: 로직 재사용을 위한 `use*` 패턴 (`useWebSocket`, `useTheme`, `useElectron`)
- **상태 관리**: Pinia 스토어
- **WebSocket 클라이언트**: SockJS + @stomp/stompjs
- **스타일**: Bootstrap 5 + SCSS, `data-bs-theme` 속성으로 다크모드 전환

### Git Workflow

- **브랜치 전략**: Git Flow (`main`, `develop`, `feature/*`, `release/*`, `hotfix/*`)
- **커밋 메시지**: Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`)
- **CI/CD**: GitHub Actions (`.github/workflows/ci.yml`) - 백엔드 테스트, 프론트엔드 린트/빌드, Docker 빌드, Trivy 보안 스캔

## Environment Variables

```bash
# 필수
GEMINI_API_KEY=<Google Gemini API 키>

# 선택 (기본값 있음)
VALKEY_HOST=localhost
VALKEY_PORT=6379
ELASTICSEARCH_URL=http://localhost:9200
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

## Service Ports

| Service            | Port |
|--------------------|------|
| Frontend (Vite)    | 3000 |
| Gateway            | 8000 |
| Chat Service       | 8080 |
| AI Summary Service | 8081 |
| Search Service     | 8082 |
| Kafka              | 9092 |
| Zookeeper          | 2181 |
| Elasticsearch      | 9200 |
| PostgreSQL         | 5432 |
| Valkey (Redis)     | 6379 |
| Prometheus         | 9090 |
| Grafana            | 3001 |
| Kibana             | 5601 |

## Message Flow

1. 클라이언트가 WebSocket으로 메시지 전송 → Chat Service
2. Chat Service가 즉시 `/topic/chat/{roomId}`로 브로드캐스트 (실시간 전달)
3. Chat Service가 Kafka `chat-messages` 토픽에 비동기 발행
4. Search Service가 Kafka에서 소비 → Elasticsearch에 인덱싱
5. AI Summary Service가 메시지 축적 (10개 임계값) → Gemini로 요약 생성 → Kafka `ai-summaries` 발행
6. Search Service가 AI 요약도 Elasticsearch에 인덱싱

## Elasticsearch Korean Search

- **Nori 토크나이저**: 한국어 형태소 분석
- **N-gram**: 1~3글자 부분 매칭
- **동의어**: 채팅↔대화↔메시지, 요약↔정리, 검색↔찾기↔조회
- **불용어**: 이, 그, 저, 것, 들, 의, 에, 를, 을, 는, 은, 가 등
- **인덱스**: `chat_messages` (설정 파일: `search-service/src/main/resources/elasticsearch/`)

## API Endpoints

### Chat Service (Port 8080)
- `GET /api/chat/rooms` - 채팅방 목록
- `GET /api/chat/rooms/{id}` - 채팅방 상세
- `POST /api/chat/rooms` - 채팅방 생성
- WebSocket `/ws` → STOMP `/app/chat.sendMessage`, `/app/chat.addUser`

### AI Summary Service (Port 8081)
- `GET /api/ai-summary/room/{roomId}` - 요약 조회
- `POST /api/ai-summary/request` - 요약 요청 (body: `{chatRoomId}`)

### Search Service (Port 8082)
- `GET /api/search/korean?query=&roomId=&page=&size=` - 한국어 검색
- `GET /api/search/ngram?query=&roomId=&page=&size=` - N-gram 검색
- `GET /api/search/messages?query=&page=&size=` - 일반 검색
- `GET /api/search/rooms/{roomId}/messages?query=` - 채팅방 내 검색
- `GET /api/search/rooms/{roomId}/time-range?start=&end=` - 시간 범위 검색

## Common Issues

- AI 요약이 동작하지 않으면 `GEMINI_API_KEY` 환경 변수 확인
- Elasticsearch 인덱스 문제 시 `IndexInitializer`가 앱 시작 시 자동 생성하므로 서비스 재시작
- Kafka 연결 실패 시 Zookeeper → Kafka 순서로 시작되었는지 확인
- 프론트엔드 WebSocket 연결은 Gateway(8000)를 통해 프록시됨
- 로컬 환경에서는 SimpleBroker 사용 (외부 STOMP 브로커 불필요)
- `.env.example` 참고하여 환경변수 설정