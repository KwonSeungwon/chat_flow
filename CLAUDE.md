# CLAUDE.md - ChatFlow Project Guide

## Project Overview

ChatFlow는 마이크로서비스 아키텍처 기반의 실시간 채팅 플랫폼이다. AI 대화 요약(Google Gemini), 한국어 전문 검색(Elasticsearch + Nori), WebSocket 실시간 통신을 핵심 기능으로 제공한다.

## Architecture

```
Frontend (Flutter Web, Port 80 / nginx)
    ↓ HTTP (Dio) / WebSocket (STOMP via stomp_dart_client)
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
- **Frontend**: Flutter 3.22+, Dart 3.3+, Riverpod 2.5, GoRouter 14, Dio 5, stomp_dart_client 2, flutter_secure_storage 9, nginx (web 서빙)
- **Data**: PostgreSQL 16 (prod) / H2 (local), Valkey 7.2 (Redis 호환), Elasticsearch 8.11 + Nori
- **Messaging**: Apache Kafka 7.4
- **AI**: LangChain4J 0.25 + Google Gemini 1.5 Flash
- **Build**: Gradle 8.5 (backend), Flutter SDK (frontend)
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
├── frontend/                # Flutter Web + Android 프론트엔드
│   ├── lib/core/            # 네트워크(Dio, STOMP), 라우터, 테마
│   ├── lib/features/        # auth, chat, search 피처 레이어
│   ├── lib/shared/          # ChatMessage, ChatRoom 모델
│   ├── android/             # Android 네이티브 (minSdk 23)
│   ├── web/                 # Flutter Web 빌드 출력 + chatflow-app.apk
│   ├── nginx.conf           # nginx 리버스 프록시 설정
│   └── Dockerfile           # nginx:alpine 컨테이너
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

> **Local 프로필 설정 (gateway-service)**: gateway-service의 `src/main/resources/application-local.yml`은 개발자별 오버라이드 용도로 gitignored 상태다. 신규 체크아웃 시 STOMP WebSocket 라우트(`/ws-native`)가 빠진 채로 시작되어 로컬 dev에서 STOMP가 404로 떨어질 수 있으니, 우선 `application-local.yml.example`을 복사해서 시작한다:
> ```bash
> cp gateway-service/src/main/resources/application-local.yml.example \
>    gateway-service/src/main/resources/application-local.yml
> ```
> 다른 서비스(chat-service / ai-summary-service / search-service)도 `application-local.yml`이 동일하게 gitignored이지만 누락 시 가시적인 오류를 내므로 별도 템플릿은 제공하지 않는다.

### Frontend (Flutter)

```bash
cd frontend

flutter pub get                                              # 의존성 설치
flutter run -d chrome                                        # 웹 개발 서버
flutter build web --release                                  # 웹 프로덕션 빌드
flutter build apk --release                                  # Android APK 빌드

# Docker 이미지 빌드 (EC2 배포용, amd64 크로스 빌드)
docker buildx build --platform linux/amd64 \
  -t chatflow-frontend:prod --load .
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

- **아키텍처**: Flutter 피처 레이어 (`lib/features/{auth,chat,search}`)
- **상태 관리**: Riverpod `StateNotifierProvider` — `authProvider`, `chatRoomsProvider`, `chatNotifierProvider(roomId)`, `searchProvider`, `themeModeProvider`
- **WebSocket**: `StompService` — stomp_dart_client, 자동 재연결(지수 백오프, 최대 10회), Web은 현재 origin에서 URL 파생
- **네트워크**: Dio + JWT Bearer 인터셉터, 401 시 secure storage 자동 삭제
- **라우팅**: GoRouter — `/login`, `/chat`, `/chat/:roomId`, `/search` (token 기반 redirect)
- **테마**: `themeModeProvider`(StateProvider) → `MaterialApp.router`의 `themeMode` 연동, AppBar 토글 버튼
- **플랫폼 분기**: `kIsWeb` 조건부 로직 (WS URL 파생, APK 다운로드 버튼)
- **조건부 import**: `apk_downloader.dart` → `dart:html` web/stub 3파일 패턴

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

| Service              | Port |
|----------------------|------|
| Frontend (nginx)     | 80   |
| Gateway              | 8000 |
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

## K3s 배포 (자체 인스턴스)

**도메인**: https://app.chatflow.ai.kr (Cloudflare → K3s)  
**네임스페이스**: `chatflow`  
**이미지 레지스트리**: `docker.io/chatflow` (pullPolicy: Never — containerd 직접 주입)

> 배포 서버 접속 정보, Cloudflare 토큰 등 민감 정보는 `.claude/CLAUDE.md` (로컬 전용) 참고.

### 전체 배포 (스크립트)
```bash
./scripts/deploy-k3s.sh all      # infra + secrets + images + helm
./scripts/deploy-k3s.sh images   # 이미지만 빌드·전송·import
./scripts/deploy-k3s.sh helm     # Helm 배포만
```

### 개별 서비스 배포 (일부 변경 시)
```bash
# 1. 변경된 서비스 이미지 빌드 (amd64 크로스 빌드)
# 백엔드:
./gradlew :chat-service:bootJar --no-daemon
docker buildx build --platform linux/amd64 --build-arg SERVICE_NAME=chat-service \
  -t docker.io/chatflow/chat-service:latest --load .

# 프론트엔드:
cd frontend && flutter build web --release && cd ..
docker buildx build --platform linux/amd64 \
  -t docker.io/chatflow/frontend:latest --load frontend/

# 2~4. 이미지 전송 → K3s import → Helm upgrade
# 상세 명령은 .claude/CLAUDE.md 참고
```

### 프론트엔드 배포 후 Cloudflare 캐시 퍼지 (필수)
```bash
# 토큰/Zone ID는 .claude/CLAUDE.md 참고
curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_PURGE_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

## Common Issues

- AI 요약이 동작하지 않으면 `GEMINI_API_KEY` 환경 변수 확인
- Elasticsearch 인덱스 문제 시 `IndexInitializer`가 앱 시작 시 자동 생성하므로 서비스 재시작
- Kafka 연결 실패 시 Zookeeper → Kafka 순서로 시작되었는지 확인
- WebSocket 연결은 `/ws-native` 경로로 Gateway 프록시됨 — SecurityConfig에서 `permitAll()` 필수
- Flutter Web에서 WS URL은 현재 origin에서 자동 파생 (별도 설정 불필요)
- Android APK는 `/chatflow-app.apk` 엔드포인트로 웹 UI에서 다운로드 가능 (nginx attachment 헤더)
- Auth hydration 완료 전 chatNotifierProvider가 생성될 경우 token=null → `joinRoom` 가드 처리됨
- `.env` 파일: `API_BASE_URL`, `WS_URL` (네이티브 Android용, 웹은 origin 자동 파생)