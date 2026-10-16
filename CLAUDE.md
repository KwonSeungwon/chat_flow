# CLAUDE.md - ChatFlow Project Guide

## Project Overview

제품명은 Gossiplog(app.gossiplog.com)로, 캐주얼 채팅 앱에 학습 게임 "위키 던전"을 더한 구성이다. 내부 코드명과 패키지는 ChatFlow(`chatflow`)를 유지한다. 마이크로서비스 아키텍처 기반의 실시간 채팅 플랫폼이며, 가입은 사용자명과 비밀번호만 받는다. WebSocket(STOMP) 실시간 채팅을 핵심 기능으로 제공한다. 검색·AI 요약 서비스와 Elasticsearch·Kafka는 제거됐다(Gateway + Chat 2서비스 구성). `/world` 학습 게임(위키 던전)은 chat-service에 포함되며 관리자는 `GAME_ADMIN_USER_IDS`로 지정한다. Android APK는 3.0.0 "Gossiplog"이다.

## Architecture

```
Frontend (Flutter Web, Port 80 / nginx)
    ↓ HTTP (Dio) / WebSocket (STOMP via stomp_dart_client)
Gateway Service (Spring Cloud Gateway, Port 8000)
    └→ Chat Service (Port 8080) - 실시간 채팅, WebSocket/STOMP
```

## Tech Stack

- **Backend**: Java 17+, Spring Boot 3.2, Spring Cloud Gateway, Spring WebSocket (STOMP)
- **Frontend**: Flutter 3.22+, Dart 3.3+, Riverpod 2.5, GoRouter 14, Dio 5, stomp_dart_client 2, flutter_secure_storage 9, nginx (web 서빙)
- **Data**: PostgreSQL 16 (prod) / H2 (local), Valkey 7.2 (Redis 호환)
- **Build**: Gradle 8.5 (backend), Flutter SDK (frontend)
- **Monitoring**: Prometheus, Grafana

## Project Structure

```
chat_flow/
├── common/                  # 공유 라이브러리 (BaseMessage, ChatMessage, ApiResponse)
├── chat-service/            # 실시간 채팅 (WebSocket + JPA 영속화)
│   ├── entity/              # ChatRoom, ChatMessageEntity
│   ├── repository/          # ChatRoomRepository, ChatMessageRepository
│   ├── controller/          # ChatController (WS), ChatRoomController (REST)
│   └── service/             # ChatService
├── gateway-service/         # API Gateway (라우팅, CORS, Circuit Breaker)
├── frontend/                # Flutter Web + Android 프론트엔드
│   ├── lib/core/            # 네트워크(Dio, STOMP), 라우터, 테마
│   ├── lib/features/        # auth, chat, command_palette, profile, world 피처 레이어
│   ├── lib/shared/          # ChatMessage, ChatRoom 모델
│   ├── android/             # Android 네이티브 (minSdk 23)
│   ├── web/                 # Flutter Web 빌드 출력 + chatflow-app.apk
│   ├── nginx.conf           # nginx 리버스 프록시 설정
│   └── Dockerfile           # nginx:alpine 컨테이너
├── monitoring/              # Prometheus 설정
├── k8s/                     # Kubernetes 매니페스트
└── scripts/                 # 배포 스크립트
```

## Build & Run Commands

### Infrastructure (Docker)

```bash
# 로컬 인프라 시작 (Valkey, PostgreSQL, etc.)
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
> chat-service도 `application-local.yml`이 동일하게 gitignored 상태다. `application-local.yml.example` 템플릿이 함께 제공되므로, 신규 체크아웃 시 동일하게 복사해서 시작한다:
> ```bash
> for svc in chat-service; do
>   cp "$svc/src/main/resources/application-local.yml.example" \
>      "$svc/src/main/resources/application-local.yml"
> done
> ```
> chat-service 템플릿의 `firebase.service-account-path`는 개발자별 절대경로로 교체한다.

### Frontend (Flutter)

```bash
cd frontend

flutter pub get                                              # 의존성 설치
flutter run -d chrome                                        # 웹 개발 서버
flutter build web --release                                  # 웹 프로덕션 빌드
flutter build apk --release                                  # Android APK 빌드

# Docker 이미지 빌드 (레거시 로컬 크로스 빌드용 — 권장 배포 경로는 GH Actions → GHCR)
docker buildx build --platform linux/amd64 \
  -t chatflow-frontend:prod --load .
```

## Key Patterns & Conventions

### Backend

- **Gradle 멀티 모듈**: `settings.gradle`에 3개 서브프로젝트 정의, `common`은 공유 라이브러리
- **공통 모듈**: `common`은 `bootJar` 비활성화, 다른 서비스에서 `implementation project(':common')`로 참조
- **프로필 기반 설정**: `application-local.yml` (개발), `application-prod.yml` (운영)
- **WebSocket**: STOMP 프로토콜, `/app/chat.sendMessage` (전송), `/topic/chat/{roomId}` (구독)
- **패키지 구조**: `com.chatflow.{서비스명}` (예: `com.chatflow.chat`, `com.chatflow.gateway`)
- **Lombok**: 전체 서비스에서 `@Data`, `@Slf4j` 등 사용

### Frontend

- **아키텍처**: Flutter 피처 레이어 (`lib/features/{auth,chat,command_palette,profile,world}`)
- **상태 관리**: Riverpod `StateNotifierProvider` — `authProvider`, `chatRoomsProvider`, `chatNotifierProvider(roomId)`, `themeModeProvider`
- **WebSocket**: `StompService` — stomp_dart_client, 자동 재연결(지수 백오프, 최대 10회), Web은 현재 origin에서 URL 파생
- **네트워크**: Dio + JWT Bearer 인터셉터, 401 시 secure storage 자동 삭제
- **라우팅**: GoRouter — `/login`, `/chat`, `/chat/:roomId`, `/world`, `/world/dungeon/:id`, `/world/admin` (token 기반 redirect)
- **테마**: `themeModeProvider`(StateProvider) → `MaterialApp.router`의 `themeMode` 연동, AppBar 토글 버튼
- **플랫폼 분기**: `kIsWeb` 조건부 로직 (WS URL 파생, APK 다운로드 버튼)
- **조건부 import**: `apk_downloader.dart` → `dart:html` web/stub 3파일 패턴

### Dev harness scripts

- **`scripts/status.sh`** — 현재 branch / develop 대비 ahead-behind / working tree dirtiness / 마지막 `develop-build.yml` 실행 결과 / TODO 카운트를 한 화면에. 새 세션 시작 시 `/load-context` 다음에 실행하면 컨텍스트가 1초만에 잡힌다.
- **`scripts/hooks/pre-push`** — touched area에 따라 backend test / flutter analyze를 자동 실행. 활성화:
  ```bash
  git config core.hooksPath scripts/hooks
  ```
  필요 시 `PRE_PUSH_SKIP=1 git push`로 건너뛸 수 있다.

### Git Workflow

- **브랜치 전략**: Git Flow (`main`, `develop`, `feature/*`, `release/*`, `hotfix/*`)
- **커밋 메시지**: Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`)
- **CI/CD**: GitHub Actions (`.github/workflows/ci.yml`) - 백엔드 테스트, 프론트엔드 린트/빌드, Docker 빌드, Trivy 보안 스캔

## Environment Variables

```bash
# 모두 기본값 있음
VALKEY_HOST=localhost
VALKEY_PORT=6379
# 위키 던전 문제 검수 관리자 users.user_id (쉼표 구분, 비면 관리자 API 전부 403). 운영은 helm values-k3s의 chat-service.gameAdminUserIds
GAME_ADMIN_USER_IDS=
```

## Service Ports

| Service              | Port |
|----------------------|------|
| Frontend (nginx)     | 80   |
| Gateway              | 8000 |
| Chat Service       | 8080 |
| PostgreSQL         | 5432 |
| Valkey (Redis)     | 6379 |
| Prometheus         | 9090 |
| Grafana            | 3001 |

## Message Flow

1. 클라이언트가 WebSocket으로 메시지 전송 → Chat Service
2. Chat Service가 즉시 `/topic/chat/{roomId}`로 브로드캐스트 (실시간 전달)

## API Endpoints

> 아래는 핵심 엔드포인트만 발췌한 것이다. 실제 API 표면은 훨씬 넓다(컨트롤러 21개): 인증/유저(AuthController, UserController — gateway), 메시지 상호작용·신고·멘션 다이제스트·예약 메시지·읽음 상태·방 초대/밴/멤버 관리·파일·프로필·FCM 등. 전체 목록은 `**/controller/*.java`를 검색하라.

### Chat Service (Port 8080)
- `GET /api/chat/rooms` - 채팅방 목록
- `GET /api/chat/rooms/{id}` - 채팅방 상세
- `POST /api/chat/rooms` - 채팅방 생성
- WebSocket `/ws` → STOMP `/app/chat.sendMessage`, `/app/chat.addUser`

### 위키 던전 (chat-service `game` 패키지, gateway `/api/game/**`)
- `GET /api/game/dungeons`, `POST /api/game/dungeons/{id}/runs`, `GET /api/game/runs/{runId}/next`, `POST /api/game/runs/{runId}/answers`, `GET /api/game/me`, `GET /api/game/leaderboard`
- 관리자: `POST /api/game/admin/questions/bulk`, `GET /api/game/admin/questions`, `PATCH /api/game/admin/questions/{id}`
- 문제는 로컬 스킬 `wiki-quiz-gen`(리포 밖)으로 만들어 bulk 업로드 → 검수 승인한 것만 출제

## K3s 배포 (자체 인스턴스)

**도메인**: https://app.gossiplog.com (Cloudflare → K3s)  
**네임스페이스**: `chatflow`  
**이미지 레지스트리**: 
- 신규(권장): `ghcr.io/<owner>/chatflow/<service>` — GH Actions가 push, K3s containerd가 pull

> 배포 서버 접속 정보, Cloudflare 토큰 등 민감 정보는 `.claude/CLAUDE.md` (로컬 전용) 참고.

### 권장 흐름 — GH Actions (로컬 Docker daemon 불필요)

1. **develop 머지 → 자동 빌드**: `.github/workflows/develop-build.yml`이 매 develop push 시 모든 서비스(amd64)를 GHCR로 push. 태그: `sha-<full>`(immutable) + `develop-latest`(mutable).
2. **수동 배포**: GitHub Actions → "Deploy to K3s (manual)" → service + tag 선택. SSH로 K3s node에서 GHCR pull + `kubectl set image` + rollout 검증.
3. **프론트엔드 배포 후 Cloudflare 캐시 퍼지** 필수 (아래).

필요 GitHub Secrets (Settings → Secrets and variables → Actions):
- `K3S_HOST`, `K3S_USER`, `K3S_SSH_KEY` (private key)
- `K3S_GHCR_USERNAME`, `K3S_GHCR_TOKEN` (read:packages PAT)

### 클러스터 부트스트랩 — `scripts/deploy-k3s.sh`
```bash
./scripts/deploy-k3s.sh infra    # k8s/infra/k3s-infra.yaml 적용 (PostgreSQL, Valkey 등)
./scripts/deploy-k3s.sh secrets  # 없는 Secret만 생성 (기존 Secret은 건너뜀)
./scripts/deploy-k3s.sh all      # infra + secrets
```
필수 env: `DEPLOY_DB_PASSWORD`, `DEPLOY_JWT_SECRET`, `DEPLOY_GATEWAY_SECRET` (`scripts/.env.deploy`, gitignored). 이미지 빌드·배포는 이 스크립트가 하지 않는다 — GH Actions 경로를 쓴다.

### 프론트엔드 배포 후 Cloudflare 캐시 퍼지 (필수)
```bash
# 토큰/Zone ID는 .claude/CLAUDE.md 참고
curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_PURGE_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

## Common Issues

- Valkey 연결 실패 시 `VALKEY_HOST`/`VALKEY_PORT`와 컨테이너 기동 여부를 확인
- WebSocket 연결은 `/ws-native` 경로로 Gateway 프록시됨 — SecurityConfig에서 `permitAll()` 필수
- Flutter Web에서 WS URL은 현재 origin에서 자동 파생 (별도 설정 불필요)
- Android APK는 `/chatflow-app.apk` 엔드포인트로 웹 UI에서 다운로드 가능 (nginx attachment 헤더)
- Auth hydration 완료 전 chatNotifierProvider가 생성될 경우 token=null → `joinRoom` 가드 처리됨
- `.env` 파일: `API_BASE_URL`, `WS_URL` (네이티브 Android용, 웹은 origin 자동 파생)