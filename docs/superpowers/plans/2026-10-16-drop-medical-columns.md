# 병원 컬럼 DROP + 다크 스플래시 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Gossiplog 리브랜딩(V20)에서 남겨 둔 병원 컬럼 `users.role`, `chat_rooms.allowed_roles`, `chat_messages.priority`를 지우고, 다크 모드를 저장한 사용자가 로딩 화면에서 밝은 화면을 보지 않게 한다.

**배경:** 운영 파드는 모두 sha-9448f92 이후 이미지라 세 컬럼을 매핑하지 않는다(사용자 승인: "이후 테스트 및 수정, 개발, 배포 과정은 별도 승인없이"). V20 계획서 후속 항목: "이 마이그레이션에서 V20의 정규화(HANDOFF→GENERAL, PATIENT_CARD·SBAR 삭제)를 한 번 더 돌린다".

## Global Constraints

- Flyway `V21__drop_medical_columns.sql`(chat-service). PostgreSQL 16. 다시 돌려도 안전하게 `IF EXISTS`를 쓴다: `ALTER TABLE IF EXISTS users DROP COLUMN IF EXISTS role;` 등. 새 DB(gateway `schema.sql`로 만든 users엔 role이 없음)에서도 성공해야 한다.
- V21 앞부분에서 V20의 1번(HANDOFF→GENERAL)과 3·4번(PATIENT_CARD·SBAR 메시지와 참조 행 삭제) 블록을 그대로 다시 실행한 뒤 컬럼을 지운다. 2번(allowed_roles NULL)은 컬럼을 지우므로 생략.
- `RoomTypeConverter`와 옛 클라이언트 호환(unknown 필드 무시, roomType GENERAL 고정, PATIENT_CARD 거절)은 유지한다. 옛 APK 사용자가 남아 있다.
- `gateway-service/src/main/resources/schema.sql` 1~2행 주석(“컬럼 DROP은 후속 릴리스”)을 현재 사실로 고친다.
- `FlywayMigrationTest`: 기대 버전 21, V21 검사(세 컬럼이 사라짐, V21 재정규화가 HANDOFF·PATIENT_CARD 행을 처리, users에 role이 없는 DB에서도 성공). Docker로 실제 PG에서 돌려 skip 0을 보인다.
- 스플래시(`frontend/web/index.html`): `localStorage`의 `flutter.chatflow-theme-mode` 값이 `"dark"`(shared_preferences 웹은 JSON 문자열로 저장하므로 `"\"dark\""` 형태일 수 있다 — 실제 저장 형식을 shared_preferences_web 소스로 확인)이면 `<html>`에 `dark` 클래스를 붙여 body·로더 배경을 `#161A26`, 글자 `#F0F2F5`, 진행바 트랙 `#2A2F45`로 바꾼다. 스크립트는 `<head>`의 스타일 바로 뒤에서 동기 실행해 첫 페인트 전에 적용. localStorage 접근은 try/catch.
- 검증: `./gradlew :chat-service:build :gateway-service:build` EXIT 0, `cd frontend && flutter test`(스플래시는 Dart 코드가 아니므로 기존 테스트 통과만), 시크릿 금지, 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

### Task 1: V21 컬럼 DROP + 다크 스플래시

- [ ] V21 작성, FlywayMigrationTest 21로, V21 테스트 추가, schema.sql 주석 갱신 → `./gradlew :chat-service:build :gateway-service:build` → Commit `feat(chat): V21 drop medical columns (role, allowed_roles, priority)`.
- [ ] 스플래시 다크 분기 → 크롬에서 localStorage 값을 바꿔 확인(로컬 `flutter build web --release` 산출물을 `python3 -m http.server`로 띄우거나 index.html만 열어서) → Commit `fix(web): dark loading screen for saved dark theme`.

### 배포

develop 머지 → develop-build → chat·gateway·frontend 태그 고정 → **pg_dump 백업** → helm upgrade(V21) → 컬럼 3개 사라짐 확인 → CF 퍼지 → E2E(가입, 방 생성, 채팅, 다크 저장 후 새로고침 로딩 화면).
