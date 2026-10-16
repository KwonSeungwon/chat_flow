# 전체 감사 결함 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 2026-10-16 전체 감사(`.superpowers/audit-2026-10-16/*.md`)에서 확인된 결함을 고치고 배포한다. 사용자 지시: "전체적으로 분석해서 오류없는지 파악하고 수정한 후 배포까지 진행", 작업 끝까지 확인 요청 금지.

**감사 보고서:** `.superpowers/audit-2026-10-16/backend-chat.md`, `gateway.md`, `frontend.md`, `contracts-infra.md` (각 항목 ID는 이 파일들의 것).

## Global Constraints

- 고칠 곳만 고친다. 각 수정에는 실패를 재현하는 테스트를 먼저 쓴다(TDD). 리팩터링·스타일 변경 금지.
- 옛 클라이언트(배포된 APK 3.0.x, 열린 웹 탭)가 깨지지 않게 한다: 응답 필드는 추가만, 제거·이름 변경 금지. 요청 검증 강화는 정상 클라이언트가 보내는 값(프론트 코드로 확인)을 통과시켜야 한다.
- DB 스키마 변경이 필요하면 Flyway V22 이후(chat-service). 기존 데이터와 충돌하는 제약(예: lower(username) 유니크)은 추가하지 않는다 — 운영에 대소문자만 다른 중복 1쌍, 규칙 밖 사용자명 4개가 있다. 사용자명 규칙은 **신규 가입에만** 적용.
- 운영 DM 방은 1개(`DM:땅콩,땅콩2`). DM 식별을 userId 기반으로 바꿔도 이 방이 같은 두 사용자에게 다시 찾아져야 한다.
- 검증: `./gradlew build` EXIT 0, `cd frontend && flutter analyze` error·warning 0(info 기준 52 이하), `flutter test` PASS, `flutter build web --release` 성공. 시크릿 금지. 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Rulings

- Ruling: chat M4(LinkPreview DNS 리바인딩)는 캐시 키만 URL 전체 SHA-256으로 고치고 리바인딩은 남긴다 — 응답에서 title/og만 뽑고 클러스터 안에 인증 없는 HTML 서비스가 없어 노출이 제한적, JDK HttpClient로 IP 고정 연결+SNI는 범위가 큼. 비용: 내부 HTTP 서비스가 생기면 재검토.
- Ruling: gateway L8(X-Real-IP 신뢰)·L12(서킷 5xx)·infra Prometheus 401은 고치지 않는다 — gateway는 ClusterIP+Cloudflare 터널로만 노출, Prometheus는 K3s에 배포돼 있지 않음, 5xx로 서킷을 열면 동작이 바뀜. 비용: 외부 노출 구성 변경 시 재검토.
- Ruling: chat L6(리더보드 반복 정답)는 correctCount를 사용자별 distinct questionId 정답 수로 바꾼다.
- Ruling: frontend L11(관리자 403 프로브)은 `GET /api/game/me` 응답에 `admin` boolean을 **추가**하고 프론트가 그것을 쓴다(Task 1이 백엔드, Task 3이 프론트). 옛 필드는 유지.
- Ruling: 프론트 C1(APK 도메인)은 코드가 아니라 로컬 `.env`(gitignored) 문제 — 컨트롤러가 `.env`를 `app.gossiplog.com`으로 고치고 APK를 다시 빌드한다. 재발 방지로 프론트 앱이 `API_BASE_URL`이 비었거나 옛 도메인일 때를 따로 처리하지는 않는다.

---

### Task 1: chat-service 보안·정합성 (backend-chat.md C1 C2 H1 H2 M1 M2 M3 M4(캐시키) L1 L2 L3 L4 L5 L6 L7 L8 + /me admin 플래그)

- [ ] C1: `StompAuthChannelInterceptor`에서 SEND destination이 `/app/`으로 시작하지 않으면 거부.
- [ ] C2: SUBSCRIBE 허용 목록 — `/user/**`, `/topic/chat/{roomId}` 및 그 하위 경로(현재 프론트·서버가 쓰는 것만: grep으로 확인)만 허용, 멤버십 검사 유지. `*`, `?`, `{` 포함 destination 및 그 외 `/topic`, `/queue` 직접 구독은 거부.
- [ ] H1: `RoomMemberRemovedEvent`(강퇴·밴) 시 그 사용자의 그 방 구독을 서버에서 끊는다(세션 레지스트리로 해당 세션 종료 또는 구독 제거 중 단순한 쪽) + participants set 제거. 대상 사용자의 다른 방은 재연결 후 다시 구독되므로 세션 종료도 허용.
- [ ] H2: DM 조회를 room_members의 두 userId 쌍으로(기존 이름 기반 방도 찾아짐), 클라이언트 targetUsername 대신 서버가 targetUserId로 사용자명을 조회(gateway users 테이블은 같은 DB — 읽기 전용 조회 가능 여부 확인, 안 되면 targetUserId만 신뢰하고 username은 표시용), DM_CREATE는 두 참가자 본인만 허용.
- [ ] M1: `@AuthenticatedUser`·`AuthInterceptor`가 SecurityContext의 검증된 principal을 쓴다(헤더가 아니라).
- [ ] M2·L2: REST 전송 content `@Size(max=10000)`, file/forward 필드 엔티티 길이와 같은 `@Size`(STOMP 경로 포함), BanRequest/ReportSubmitRequest `@Size`, `DataIntegrityViolationException`→400(or 409), `MaxUploadSizeExceededException`→413 매핑.
- [ ] M3: emoji `@Size(max=32)`·메시지당 리액션 종류 상한(50), 동시 토글 lost update 방지(행 락 또는 @Version 재시도).
- [ ] M4: 링크 프리뷰 캐시 키를 URL 전체 SHA-256으로.
- [ ] L1: 신고·DM 동시 생성 중복을 500 없이 멱등 처리(savepoint/REQUIRES_NEW 또는 ON CONFLICT).
- [ ] L3: 끝난 run의 마지막 답 재전송은 저장된 결과를 돌려준다, Redis 반영은 afterCommit.
- [ ] L4: `/unread-counts`는 사용자가 볼 수 있는 방만.
- [ ] L5: 메시지 수정·삭제는 현재 멤버이고 밴이 아닐 때만.
- [ ] L6: Ruling대로. L7: 출제 시 APPROVED가 아니면 건너뜀. L8: 방 비밀번호 검증 (userId, roomId)당 분당 5회 제한(Redis).
- [ ] `GET /api/game/me` 응답에 `admin`(GAME_ADMIN_USER_IDS 포함 여부) 추가.
- [ ] `./gradlew :common:build :chat-service:build` → 커밋(항목별로 나눠도 됨).

### Task 2: gateway 인증 (gateway.md H1 M2 M3 M4 M5 L6 L7 L9 L10 L13)

- [ ] H1: 레이트 리밋 경로 비교를 디코딩된 경로(`pathWithinApplication` 세그먼트 디코딩) 또는 PathPattern으로. `%6Cogin` 회귀 테스트.
- [ ] M2: logout은 active_jti 값이 그 토큰 jti와 같을 때만 지운다(Lua compare-and-delete), 블랙리스트된 토큰은 no-op.
- [ ] M3: 신규 가입 username trim, 2~20자, `[A-Za-z0-9가-힣_.-]`만, 대소문자 무시 중복 거부, 중복 경합 `DataIntegrityViolation`→409, 비밀번호 최대 72바이트. 프론트 가입 폼 검증과 메시지가 맞는지 확인(프론트 변경이 필요하면 Task 3 브리프에 없으므로 서버 메시지로 충분히 안내).
- [ ] M4: `PUT /api/auth/profile`의 profileImageUrl을 `ProfileUpdateRequest.validate`와 같은 규칙으로, 그 규칙에서 `//` 시작·`http:` 거부(https 또는 `/api/files/` 상대경로만). 프론트가 실제로 저장하는 값 형식을 확인해 통과시킨다.
- [ ] M5: 비밀번호 변경 시 현재 세션 외 모든 세션 무효화(active_jti 회전 + 새 토큰을 응답에 추가하거나, 프론트 흐름을 확인해 깨지지 않는 쪽), `/api/auth/password` 사용자당 분당 5회 제한.
- [ ] L6: 만료·잘못된 토큰 logout → 204/401(500 금지). L7: INCR+EXPIRE 원자화. L9: 없는 사용자도 더미 bcrypt. L10: 쿼리 `?token=`은 `/ws`, `/ws-native` 경로만. L13: CorsWebFilter 최우선 순서.
- [ ] `./gradlew :gateway-service:build` → 커밋.

### Task 3: 프론트 (frontend.md H2 H3 M4 M5 M6 M7 L8 L9 L10 L11 + contracts L4 503 매핑)

- [ ] H2: hydration 전에는 Navigator 대신 스플래시(MaterialApp.router builder), ChatPage 자체 가드는 hydration 이후에만, 비로그인 리다이렉트는 모든 경로를 `?redirect=`로 보존. 회귀 테스트: 로그인 상태 `/chat/<room>` 새로고침이 그 방에 남음, 비로그인 `/world`는 게임 API를 부르지 않고 `/login?redirect=/world`.
- [ ] H3: 재시도 소진 후에도 앱 resume·온라인 복귀·주기적(예: 30초) 재시도로 다시 연결, 재연결 성공 시 현재 방 히스토리 재조회 후 merge. `AppStompService`도 같은 구조면 함께.
- [ ] M4: `WebUnloadHandler.register`를 Firebase 초기화 뒤로 옮기거나 오류 처리, 로그인 후 토큰 캐시 갱신.
- [ ] M5: 활성 방은 unread setCount 건너뜀, 히스토리 로드 후 마지막 메시지 id로 markRoomRead.
- [ ] M6: 오프라인 큐 flush는 SUBSCRIBE·addUser 뒤. M7: 전달 칩 Flexible+ellipsis. L8: rollback 전 mounted 확인. L9: dispose 뒤 SVG 로드 결과 폐기. L10: 스레드 답글을 메인 타임라인에 합치지 않거나 로드 범위 안일 때만.
- [ ] L11: `isGameAdminProvider`가 `/api/game/me`의 `admin`을 쓴다(없으면 false, 옛 서버 호환). contracts L4: 게임 API 503(`SERVICE_UNAVAILABLE`)도 "던전 관리인이 자리를 비웠다" 안내.
- [ ] `flutter analyze`·`flutter test`·`flutter build web --release` → 커밋.

### Task 4: 인프라·스크립트 (contracts-infra.md M2 M3 L5 + nginx /ws 타임아웃)

- [ ] 로컬 스크립트(start-chatflow.sh, scripts/start-local.sh, status/stop)의 삭제된 npm 프론트 실행을 Flutter(`flutter run -d chrome` 또는 web build 서빙)로 바꾸거나 해당 단계를 제거.
- [ ] `deploy-k3s.sh` secrets: `VALKEY_PASSWORD`가 비면 무작위 생성(또는 필수로 실패), kubectl 호출을 다른 단계와 같은 방식으로.
- [ ] develop-build.yml Flutter를 ci.yml과 같은 3.41.6으로 고정, 프론트 이미지 빌드 전에 `flutter test` 실행.
- [ ] helm frontend configmap과 frontend/nginx.conf의 `/ws` 프록시에 `proxy_read_timeout`/`proxy_send_timeout` 3600s(없을 때만).
- [ ] `helm template` 렌더 확인, `bash -n` 스크립트 문법 확인 → 커밋.

### 배포

develop 머지(4개) → develop-build → 3개 태그 고정 → pg_dump → helm upgrade → CF 퍼지 → E2E(로그인·가입 규칙, `%6Cogin` 429, STOMP 위조 SEND 거부·와일드카드 구독 거부, DM, 채팅 송수신, 새로고침 시 방 유지, 월드·관리자 403 콘솔 없음) → `.env` 고친 APK 3.0.2 빌드·커밋·배포 → APK 내 URL 확인.
