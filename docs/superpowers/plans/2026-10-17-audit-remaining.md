# 감사 잔여 항목 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 2026-10-16~17 전체 감사에서 판단(ruling)으로 남겨 두었거나 리뷰가 기록만 한 항목을 모두 고치고 배포한다. 사용자 지시: "남은 것들도 다 고쳐서 배포까지", 확인 요청 금지.

**배경 문서:** `docs/superpowers/plans/2026-10-16-full-audit-fixes.md`(이전 계획과 Rulings), 아카이브 `.superpowers/notes/sdd-2026-10-16-full-audit-fixes.tgz`(감사 보고서·리뷰 기록).

## Global Constraints

- 고칠 곳만 고친다. 각 수정은 실패 테스트를 먼저 쓴다(TDD). 리팩터링·스타일 변경 금지.
- 옛 클라이언트(APK 3.0.x, 열린 웹 탭) 호환: 응답 필드는 추가만. 옛 앱이 깨지지 않게 한다(새 응답 필드를 무시해도 동작).
- DB 제약 추가 금지(운영에 대소문자만 다른 사용자명 1쌍). 스키마 변경이 필요하면 Flyway V22(chat-service).
- 운영 노출 구조: Cloudflare 터널 → traefik ingress(`/api`, `/ws` → gateway, `/` → frontend nginx) → ClusterIP 서비스. replicaCount 1.
- 검증: `./gradlew build` EXIT 0, `flutter analyze` error·warning 0(≤52), `flutter test` PASS, `flutter build web --release` 성공. 시크릿 금지. 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Rulings

- Ruling: 대소문자 중복 가입 경합은 DB 제약 대신 gateway가 가입 시 `lower(username)` 키로 Redis 잠금(SET NX, 짧은 TTL)을 잡아 직렬화한다. Redis 장애면 기존처럼 진행(fail-open).
- Ruling: 비밀번호 변경은 새 토큰을 응답에 **추가**하고 현재 jti를 블랙리스트한다. 새 프론트는 응답 토큰으로 교체한다. 옛 앱은 다음 요청에서 401로 로그아웃된다(비밀번호를 바꾼 옛 앱 사용자만 재로그인 — 허용).
- Ruling: 서킷 브레이커는 502·503·504만 실패로 집계한다(500은 앱 오류라 제외).
- Ruling: STOMP "Unauthorized" 문자열 판별은 서버 거부 메시지를 상수로 고정하고 프론트 판별과 맞춘다는 주석·테스트로 계약을 잡는다(실패 시 계속 재시도하는 안전한 방향이라 구조 변경은 안 함).

---

### Task 1: chat-service

- [ ] LinkPreview SSRF DNS 리바인딩: 검증한 IP로만 연결되게 한다(예: 해석한 InetAddress 중 공인 IP 하나로 연결하고 Host 헤더/SNI는 원래 호스트 — JDK HttpClient로 어려우면 `java.net.http` 대신 프로젝트에 이미 있는 HTTP 클라이언트나 `HttpURLConnection` + 커스텀 SocketFactory/`InetAddress` 고정 등 가장 작은 안전한 방법). 리다이렉트도 각 hop마다 같은 검증. 회귀 테스트: 첫 해석은 공인, 두 번째는 사설인 리졸버에서 사설 연결이 일어나지 않음.
- [ ] `UserDirectory`: users 조회 실패 시 클라이언트 값을 믿는 fallback은 "테이블이 없음(42P01)"일 때만, 그 밖의 SQL 오류(권한 42501 등)는 실패로 처리(DM 생성 503 또는 400).
- [ ] `/replies`(MessageInteractionController ~111-119)가 JPA 엔티티를 그대로 직렬화 → 기존 메시지 응답 DTO로. 응답 JSON 필드는 프론트가 읽는 것과 같게(프론트 thread_panel 파싱 확인, 필드 추가만).
- [ ] STOMP CONNECT 거부 메시지를 상수(`"Unauthorized"`)로 두고 테스트로 고정(Ruling).
- [ ] FlywayMigrationTest V21 재정규화 테스트에 SBAR 메시지와 참조 행(mention, report, edit_history) 시드 추가.
- [ ] `RoomVisibilityService` 미사용 import 제거(Checkstyle 경고).
- [ ] `/actuator/prometheus` permitAll(클러스터 내부에서만 닿음 — ingress가 /actuator를 라우팅하지 않음을 확인).
- [ ] `./gradlew :common:build :chat-service:build` → 커밋.

### Task 2: gateway

- [ ] 가입 대소문자 중복 경합: Ruling대로 Redis 잠금. 테스트: 같은 lower 이름 동시 가입 중 하나만 성공.
- [ ] 비밀번호 변경: Ruling대로 새 토큰 응답 추가 + 현재 jti 블랙리스트 + active_jti 회전(다른 세션도 무효). 응답 필드 이름은 로그인 응답과 같은 `token`.
- [ ] 프로필·비밀번호 lost update: `findByUsername` → 엔티티 전체 `save` 대신 필요한 컬럼만 UPDATE(R2DBC `@Query` 또는 DatabaseClient).
- [ ] `ClientIpResolver`: `X-Real-IP` 무조건 신뢰 제거. `CF-Connecting-IP` → (없으면) `X-Forwarded-For`의 첫 값 → remote address 순. 이 순서가 ingress/터널 구성에서 실제 클라이언트 IP를 주는지 코드 주석으로 근거를 남김.
- [ ] 서킷 브레이커 statusCodes 502,503,504(Ruling), 파일 라우트 TimeLimiter를 업로드 한도(55MB)에 맞게 120s.
- [ ] `/actuator/prometheus` permitAll(gateway 관리 경로가 ingress로 노출되지 않음을 확인 — 노출되면 하지 않고 보고).
- [ ] 로그인 사용자 열거 타이밍(이미 더미 bcrypt면 skip) 확인.
- [ ] `./gradlew :gateway-service:build` → 커밋.

### Task 3: 프론트 + 인프라

- [ ] 비밀번호 변경 응답의 `token`이 있으면 저장 토큰·상태·STOMP 토큰을 교체(Task 2 인터페이스: 응답 envelope 안 `token` 문자열, 없으면 기존 동작).
- [ ] 사이드바: 라우트가 다시 current가 되면(로비 복귀) 즉시 1회 갱신. 다이얼로그·팝업(PageRoute가 아닌 route)이 위에 떠 있을 때는 폴링을 멈추지 않는다(`RouteAware`의 didPushNext/didPopNext로 PageRoute만 가림으로 취급).
- [ ] STOMP 재연결: tokenProvider 예외 시에도 다음 재시도 예약(try/catch). 웹 `online` 이벤트에 즉시 재연결. 재연결 refetch가 50건을 넘는 공백이면 이전 페이지를 겹칠 때까지 이어 받기(상한 5페이지). refetch merge가 이미 있는 메시지의 수정 내용·삭제 상태를 갱신.
- [ ] 서버가 보내지 않는 `/topic/chat/{id}/errors` 구독: 서버 송신처를 grep해 정말 없으면 제거(있으면 유지하고 보고).
- [ ] 수동 `['data']` 파싱 5곳을 기존 `apiResponse*` 헬퍼로(envelope/raw 모두 처리하는 쪽).
- [ ] 다크 저장 사용자: 스플래시 뒤 첫 프레임이 라이트로 번쩍이지 않게 — ThemeModeNotifier 초기값을 동기 소스(웹: localStorage 동일 키, 네이티브: 기존 비동기 유지)에서 읽는다.
- [ ] 인프라: 루트 `.dockerignore`(build/, .gradle/, frontend/build, node_modules, .superpowers, .playwright-mcp, *.apk 제외 — Docker 빌드가 쓰는 경로는 남김: 루트 Dockerfile 확인), 두 서비스 JVM 시간대 UTC 명시(helm deployment `JAVA_TOOL_OPTIONS` 또는 기존 JVM 옵션에 `-Duser.timezone=UTC`).
- [ ] `flutter analyze`·`flutter test`·`flutter build web --release`, `helm template` → 커밋.

### 배포

develop 머지 → CI → 태그 고정 → pg_dump → helm upgrade → CF 퍼지 → E2E(링크 프리뷰 정상 URL, 비밀번호 변경 후 계속 사용 가능, 로비 복귀 즉시 갱신, 다이얼로그 중 폴링 유지, 다크 새로고침) → APK 3.0.4.
