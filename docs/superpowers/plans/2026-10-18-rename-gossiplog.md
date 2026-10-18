# chatflow → gossiplog 이름 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 코드·런타임 키·배포 인프라에서 `chatflow` 흔적을 없애고 `gossiplog`로 통일한다(사용자 선택 A+B+C, 2026-10-18). 확인 요청 금지. 데이터 유실 허용(사용자 1인 사이트).

## 범위

- A 코드·문서: Java 패키지 `com.chatflow` → `com.gossiplog`, 클래스·식별자·주석·로그·문서·스크립트. Dart 패키지 `chatflow` → `gossiplog`(`package:` import 포함), `ChatFlowApp` 등. Gradle `rootProject.name` → `gossiplog`. README/CLAUDE.md/frontend README.
- B 런타임 키: Redis 키 접두사 `chatflow:` → `gossiplog:`(이전 없음 — 새 Valkey로 시작), 웹/네이티브 저장 키 `chatflow-*` → `gossiplog-*`(사용자는 다시 로그인·테마 재설정 — 허용), Lua·STOMP 등 문자열 상수, 웹 `index.html`의 `#chatflow-loader`, localStorage 키 `flutter.chatflow-theme-mode`, APK 경로 `/chatflow-app.apk` → `/gossiplog-app.apk`(파일명·nginx·다운로드 버튼).
- C 인프라: helm 차트 디렉터리·이름 `helm/chatflow` → `helm/gossiplog`, 릴리스 `gossiplog`, 네임스페이스 `gossiplog`, 리소스 이름 접두사 `gossiplog-`, Secret 이름 `gossiplog-*-secret`, `k8s/namespace.yaml`·`k8s/infra/k3s-infra.yaml`, GHCR 이미지 `ghcr.io/kwonseungwon/gossiplog/<service>`, `.github/workflows/*`(IMAGE_PREFIX, NAMESPACE, DEPLOY 이름), `scripts/*`, monitoring 설정.

## 범위 밖 (그대로 둔다 — 이유를 README "Internal identifiers" 절에 한 줄씩)

- Android `applicationId`/`namespace` `ai.chatflow.app`과 Firebase(`google-services.json`): 바꾸면 다른 앱이 되어 재설치·Firebase 재등록 필요.
- PostgreSQL 데이터베이스·사용자 이름 `chatflow`(Secret 값): 값은 유지하고 Secret **이름**만 바꾼다.
- GitHub 리포 이름 `chat_flow`, 로컬 디렉터리.
- `.claude/` 하네스 렌더 파일(에이전트·스킬 정의, personal-harness-kit 관리)과 `docs/superpowers/**` 이전 계획 기록.

## Global Constraints

- 동작 변경 없음 — 이름만. 바꾼 뒤 `./gradlew build` EXIT 0, `flutter analyze` error·warning 0(≤52), `flutter test` PASS, `flutter build web --release`, `helm template gossiplog helm/gossiplog -f helm/gossiplog/values-k3s.yaml` 성공.
- 남은 흔적 검사: `git grep -in chatflow -- . ':!docs/superpowers' ':!.claude' ':!frontend/android/app/google-services.json'`의 결과는 범위 밖 항목(applicationId/namespace, DB 이름 값, 리포 URL, 위 README 절)만이어야 한다.
- 시크릿 값 금지. 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

### Task 1: 백엔드 (chat-service, gateway-service, common, Gradle)

- [ ] `git mv`로 `src/{main,test}/java/com/chatflow` → `com/gossiplog`(세 모듈), package·import 일괄 변경, 리소스(`application*.yml`의 로거명·키 접두사·서비스명, Flyway 위치 등) 변경. Redis 키·Lua·상수의 `chatflow:` → `gossiplog:`. 메트릭 태그·로그 문자열.
- [ ] `settings.gradle` rootProject 이름, 서비스 jar 이름이 바뀌면 루트 `Dockerfile`이 참조하는 jar 경로와 맞춘다(Task 3과 인터페이스: jar 파일명을 보고서에 적는다).
- [ ] `./gradlew build` → 커밋.

### Task 2: 프론트 (frontend/)

- [ ] pubspec `name: gossiplog`, 모든 `package:chatflow/` import, 클래스·식별자(`ChatFlowApp` 등), 저장 키, 테마 키(웹 동기 읽기 키 포함), `web/index.html` 로더 id/스크립트 키, APK 다운로드 경로 `/gossiplog-app.apk`, `web/chatflow-app.apk` → `web/gossiplog-app.apk`(git mv), nginx.conf, Dockerfile, 테스트. Android applicationId/namespace·Firebase 파일은 건드리지 않는다.
- [ ] `flutter analyze`·`flutter test`·`flutter build web --release` → 커밋.

### Task 3: 인프라·CI·문서

- [ ] `git mv helm/chatflow helm/gossiplog`, Chart/서브차트 이름·헬퍼·리소스 접두사·values(namespace `gossiplog`, image repository `ghcr.io/kwonseungwon/gossiplog/...`, Secret 이름 `gossiplog-*-secret`, 프론트 configmap의 upstream 서비스명·APK location). 프론트 Service에 `clusterIP` 값을 지정할 수 있게(values-k3s에 `10.43.4.13` — Cloudflare 터널이 이 IP를 가리킴) 한다.
- [ ] `k8s/namespace.yaml`, `k8s/infra/k3s-infra.yaml`(namespace, Secret 이름), `scripts/*`(deploy-k3s.sh 네임스페이스·Secret 이름, start/stop/status), `.github/workflows/*`(IMAGE_PREFIX, NAMESPACE, DEPLOY 이름, 차트 경로), 루트 `Dockerfile`(Task 1 jar 이름), `monitoring/`, `docker-compose*.yml`.
- [ ] README·CLAUDE.md·frontend/README의 이름·경로·명령, README "Internal identifiers" 절.
- [ ] `helm template`·`bash -n`·워크플로 YAML 파싱 → 커밋.

### 배포 (컨트롤러)

1. develop 머지 → develop-build(새 GHCR 경로) → 이미지 익명 pull 가능 확인(공개 아니면 패키지 공개 전환 또는 imagePullSecret).
2. 백업: 옛 PG `pg_dump`.
3. 새 네임스페이스 `gossiplog` + Secret 복제(값 그대로, 이름만 `gossiplog-*`) + infra(PG, Valkey) 적용 → PG 복원.
4. 옛 릴리스 `chatflow` 삭제(10.43.4.13 해제) → `helm install gossiplog`(frontend clusterIP 10.43.4.13) → 터널 무변경으로 `app.gossiplog.com` 응답 확인.
5. 옛 네임스페이스 `chatflow` 삭제(PV 정리). CF 퍼지. E2E(로그인 화면, 가입/로그인, 채팅, 월드 8개 건물·문제 존재, APK `/gossiplog-app.apk`). 메모리·로컬 `.claude/CLAUDE.md`(배포 명령의 네임스페이스) 갱신.
