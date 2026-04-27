# Operator Toolkit (Phase 1) — Design Spec

- **Date**: 2026-04-27
- **Status**: Draft (awaiting user approval)
- **Phase**: Phase 1 of 3 (운영/관리 + UX 폴리시 묶음)
- **Owner**: seungwon
- **Goal**: ChatFlow에 방장 운영 도구 (권한 체계, 멤버 관리, ban, 신고 큐)와 모바일 운영 진입점을 도입한다.

---

## 1. Problem & Motivation

ChatFlow는 메시지/검색/AI 요약/반응/핀/편집 등 사용자 가시 기능은 풍부하지만, **채팅방 운영(operator) 측면이 비어있다**:

- 방장과 일반 멤버가 권한적으로 동일 — 누가 무엇을 할 수 있는지 명확하지 않다.
- 분란/스팸/괴롭힘 발생 시 사용자가 손쓸 방법이 없다 (강퇴, 뮤트, ban, 신고 모두 부재).
- 모바일 웹에서 멤버 리스트조차 자연스럽게 진입하기 어렵다.

운영 도구가 부재하면 방이 망가졌을 때 사용자 신뢰가 빠르게 무너진다. Phase 1은 **이 신뢰 베이스라인**을 깔고, 이후 Phase 2/3 (UX 폴리시, 검색 필터, a11y)의 토대 (멤버 관리 UI, 권한 체크 미들웨어 등)가 된다.

---

## 2. Scope

### In scope
- 권한 체계: `OWNER` / `MODERATOR` / `MEMBER` 3단계
- 멤버 관리: 강퇴, 뮤트(시간 한정), 모더레이터 위임/해제
- 차단: ban 추가/해제 + 재입장 차단
- 신고: 메시지 신고 → 방별 모더레이터 큐
- 모바일 운영 진입점: 사이드바 → 멤버 시트 / 방 관리 시트 (풀스크린 바텀시트)

### Out of scope (Phase 2/3)
- 사용자 프로필 (아바타, 상태 메시지, 한 줄 소개) — Phase 2
- 드래그-드롭 업로드, 클립보드 이미지 붙여넣기 — Phase 2
- 명령 팔레트 (Cmd+K), 키보드 단축키 — Phase 2
- 검색 필터 (보낸이/날짜/타입) — Phase 3
- 표준 a11y (WCAG 색 대비, ARIA 풀패스, 스크린 리더 흐름) — Phase 3 (단, Phase 1 구현 시 베이스라인은 자연스럽게 적용)
- i18n (다국어) — Phase 3
- 전역 admin 큐 — Phase 3 (Phase 1은 방별 큐만)
- 커스텀 뮤트 시간 입력 — Phase 2 (Phase 1은 5/30/60분 프리셋)
- DM 운영 기능 — DM은 영원히 권한 개념 없음

---

## 3. Architecture

### 3.1 서비스 위치
- **chat-service**에 운영 도메인 추가 (별도 마이크로서비스 분리 X — RoomMemberEntity와 같은 트랜잭션 경계 안에 둔다)
- 새 서비스 클래스:
  - `RoomPermissionService` — 권한 매트릭스 검증, 역할 변경
  - `MemberManagementService` — 강퇴, 뮤트, 모더레이터 위임
  - `RoomBanService` — ban 추가/해제, 재입장 시 차단
  - `MessageReportService` — 신고 제출, 큐 조회, 상태 업데이트
- 기존 `UserPresenceService.join`에 ban 게이트 삽입
- 기존 `ChatService.sendMessage`(또는 `MessageSenderService`)에 mute 게이트 삽입

### 3.2 게이트 흐름
```
[Join (STOMP /app/chat.addUser)]
  → UserPresenceService.join
  → RoomBanService.isBanned? → yes: ROOM_BANNED 에러 broadcast + return
  → 기존 만석/DM 분기...

[Send Message (STOMP /app/chat.sendMessage 또는 REST POST /messages)]
  → MessageSenderService.send
  → RoomMemberEntity.mutedUntil > now? → yes: MUTED 에러 응답 + return
  → 기존 검열/필터/persistence...

[강퇴 (DELETE /api/chat/rooms/{roomId}/members/{userId})]
  → RoomPermissionService.requireRole(OWNER, MOD)
  → MemberManagementService.kick
  → RoomMemberRepository.delete
  → SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/kicked", payload)
  → SimpMessagingTemplate.convertAndSend("/topic/chat/{roomId}/members", refreshed_list)

[Ban (POST /api/chat/rooms/{roomId}/bans)]
  → RoomPermissionService.requireRole(OWNER, MOD)
  → RoomBanService.ban (= kick + insert ban row, 단일 트랜잭션)
  → 같은 STOMP 이벤트 emit
```

---

## 4. Data Model

### 4.1 V6 마이그레이션 SQL (단일 파일)

`chat-service/src/main/resources/db/migration/V6__operator_toolkit.sql`:

```sql
-- 1) room_members에 역할/뮤트 컬럼 추가
ALTER TABLE room_members
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN muted_until TIMESTAMP NULL;

-- 2) 기존 chat_rooms.created_by 기반 OWNER 백필
UPDATE room_members rm
SET role = 'OWNER'
FROM chat_rooms cr
WHERE rm.room_id = cr.id
  AND rm.user_id = cr.created_by;

-- 3) ban 테이블
CREATE TABLE room_bans (
    room_id    VARCHAR(50) NOT NULL,
    user_id    VARCHAR(36) NOT NULL,
    banned_by  VARCHAR(36) NOT NULL,
    reason     VARCHAR(255),
    banned_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_room_bans_room FOREIGN KEY (room_id)
        REFERENCES chat_rooms(id) ON DELETE CASCADE
);

-- 4) 메시지 신고 테이블
CREATE TABLE message_reports (
    id           BIGSERIAL PRIMARY KEY,
    message_id   VARCHAR(36) NOT NULL,
    room_id      VARCHAR(50) NOT NULL,
    reported_by  VARCHAR(36) NOT NULL,
    reason       VARCHAR(50) NOT NULL,
    comment      VARCHAR(500),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolved_by  VARCHAR(36),
    resolved_at  TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_message_reports_room_status
    ON message_reports(room_id, status);
CREATE INDEX idx_message_reports_message
    ON message_reports(message_id);
```

### 4.2 JPA 엔티티

**`RoomMemberEntity` 확장**:
```java
@Column(name = "role", length = 20, nullable = false)
@Enumerated(EnumType.STRING)
private RoomRole role;          // OWNER, MODERATOR, MEMBER

@Column(name = "muted_until")
private LocalDateTime mutedUntil;
```

**`RoomBanEntity` (신규, @IdClass(RoomBanId)):**
```java
@Id String roomId;
@Id String userId;
String bannedBy;
String reason;
LocalDateTime bannedAt;
```

**`MessageReportEntity` (신규):**
```java
@Id @GeneratedValue Long id;
String messageId;
String roomId;
String reportedBy;
@Enumerated(EnumType.STRING) ReportReason reason;
String comment;
@Enumerated(EnumType.STRING) ReportStatus status; // PENDING/RESOLVED/DISMISSED
String resolvedBy;
LocalDateTime resolvedAt;
LocalDateTime createdAt;
```

### 4.3 Enum
```java
enum RoomRole { OWNER, MODERATOR, MEMBER }
enum ReportReason { SPAM, HARASSMENT, INAPPROPRIATE, OTHER }
enum ReportStatus { PENDING, RESOLVED, DISMISSED }
```

---

## 5. Permission Matrix

| 액션 | OWNER | MOD | MEMBER | 비고 |
|---|:-:|:-:|:-:|---|
| 메시지 발송 | ✅ | ✅ | ✅ | 뮤트 아닐 때 |
| 자기 메시지 편집/삭제 | ✅ | ✅ | ✅ | 기존 동작 유지 |
| 타인 메시지 삭제 | ✅ | ✅ | ❌ | |
| 멤버 강퇴 (kick) | ✅ | ✅ | ❌ | |
| 멤버 뮤트 (mute) | ✅ | ✅ | ❌ | 5/30/60분 프리셋 |
| 멤버 ban / 해제 | ✅ | ✅ | ❌ | |
| 모더레이터 위임/해제 | ✅ | ❌ | ❌ | OWNER만 |
| 방 삭제 | ✅ | ❌ | ❌ | 기존 동작 유지 |
| 신고 처리 (resolve/dismiss) | ✅ | ✅ | ❌ | |
| 신고 제출 | ✅ | ✅ | ✅ | |

### 룰
- DM(`RoomType.DIRECT`) 방 — 모든 운영 API 거부 (HTTP 400 `ROOM_TYPE_NOT_SUPPORTED`)
- OWNER 1명 강제 — 위임 시 기존 OWNER 자동 강등 → MOD
- OWNER 자기 강퇴/자기 강등 금지 — 방 삭제로 유도
- ban = 강퇴 + 재입장 차단 (단일 트랜잭션)
- MOD가 OWNER에게 액션 금지 (강퇴/뮤트/ban 모두)

---

## 6. REST API

베이스: `/api/chat`

### 6.1 멤버 관리
```
GET    /rooms/{roomId}/members
       → 200 [{ userId, username, role, mutedUntil }]
       권한: 방 멤버 누구나

PATCH  /rooms/{roomId}/members/{userId}/role
       body: { role: "MODERATOR" | "MEMBER" }
       → 200 OK
       권한: OWNER

DELETE /rooms/{roomId}/members/{userId}
       → 204 No Content
       권한: OWNER, MOD (단 OWNER는 본인 X, MOD는 OWNER 대상 X)

POST   /rooms/{roomId}/members/{userId}/mute
       body: { minutes: 5 | 30 | 60 }
       → 200 { mutedUntil: "2026-04-27T..." }
       권한: OWNER, MOD

DELETE /rooms/{roomId}/members/{userId}/mute
       → 204
       권한: OWNER, MOD
```

### 6.2 Ban
```
GET    /rooms/{roomId}/bans
       → 200 [{ userId, username, bannedBy, reason, bannedAt }]
       권한: OWNER, MOD

POST   /rooms/{roomId}/bans
       body: { userId, reason? }
       → 201 { userId, bannedAt }
       권한: OWNER, MOD
       동작: kick + ban (단일 트랜잭션)

DELETE /rooms/{roomId}/bans/{userId}
       → 204
       권한: OWNER, MOD
```

### 6.3 신고
```
POST   /messages/{messageId}/reports
       body: { reason: "SPAM"|"HARASSMENT"|"INAPPROPRIATE"|"OTHER", comment? }
       → 201 { reportId }
       권한: 메시지 속한 방 멤버 누구나 (자기 메시지 신고 불가)

GET    /rooms/{roomId}/reports?status=PENDING
       → 200 [{ id, messageId, messageContent, messageAuthor, reportedBy, reason, comment, createdAt, status }]
       권한: OWNER, MOD

PATCH  /reports/{reportId}
       body: { status: "RESOLVED" | "DISMISSED" }
       → 200
       권한: OWNER, MOD (해당 방의)
```

### 6.4 에러 응답
- `403 PERMISSION_DENIED` — 권한 부족
- `404 NOT_FOUND` — 방/멤버/메시지/신고 없음
- `400 ROOM_TYPE_NOT_SUPPORTED` — DM 방 운영 API 호출
- `400 SELF_TARGET_NOT_ALLOWED` — OWNER 자기 강퇴 등
- `409 OWNER_ALREADY_EXISTS` — OWNER 위임 충돌 (방어용)

---

## 7. STOMP 이벤트

### 7.1 Topic broadcast
- `/topic/chat/{roomId}/members`
  ```json
  {
    "type": "MEMBER_LIST_UPDATED",
    "members": [{ "userId", "username", "role", "mutedUntil" }],
    "timestamp": "..."
  }
  ```
  발생: 강퇴/ban/역할변경/입장/퇴장

### 7.2 User-specific queue
- `/user/queue/kicked`
  ```json
  { "roomId", "reason": "KICKED"|"BANNED", "by": "username" }
  ```
  → 클라이언트: 현재 화면이 그 방이면 사이드바로 라우팅 + SnackBar

- `/user/queue/muted`
  ```json
  { "roomId", "mutedUntil": "...", "by": "username" }
  ```
  → 클라이언트: 입력창 비활성화 + 만료까지 카운트다운 (또는 만료 시점 폴링)

### 7.3 기존 게이트와의 관계
- `UserPresenceService.join`에서 ban 체크 → 기존 `ROOM_FULL_DM` 에러 패턴 따라 `/topic/chat/{roomId}/errors` `{type: "ROOM_BANNED"}` broadcast
- 만석 분기보다 먼저 검사 (banned면 만석 분기 도달 X)

---

## 8. Frontend UI

### 8.1 멤버 관리 시트
- **트리거**: 사이드바 방 헤더 "멤버 N명" 영역 클릭 / 메시지 버블의 사용자 이름 탭
- **모바일 웹**: `showModalBottomSheet` 풀스크린 (drag handle 포함)
- **데스크톱 웹**: `showDialog` 모달 (max-width 480)
- **레이아웃**: `ListView`
  - 행: `CircleAvatar` + 이름 + 역할 배지(`OWNER` 금색, `MOD` 보라) + ⋯ 액션 메뉴
  - 액션 메뉴 (역할별 분기):
    - OWNER가 봄: MEMBER → [모더레이터 위임 / 뮤트 / 강퇴 / ban]
    - OWNER가 봄: MOD → [모더레이터 해제 / 뮤트 / 강퇴 / ban]
    - MOD가 봄: MEMBER → [뮤트 / 강퇴 / ban]
    - MOD/MEMBER가 봄: 본인/OWNER → 액션 메뉴 비표시
- **확인 다이얼로그**: 강퇴/ban은 확인 단계 1번 (오작동 방지)

### 8.2 메시지 신고 다이얼로그
- **트리거**: 메시지 버블 long-press(모바일) / 우클릭 또는 ⋯ 버튼(데스크톱) → "신고"
- **자기 메시지**: "신고" 메뉴 비표시
- **폼**:
  - 사유 `ChoiceChip` (스팸 / 괴롭힘 / 부적절한 콘텐츠 / 기타)
  - 코멘트 `TextField` (선택, 500자)
  - 제출 버튼
- **결과**: 토스트 "신고 접수됨"

### 8.3 모더레이터 큐 (방 관리)
- **진입**: 사이드바 방 헤더 ⚙ → "방 관리" (OWNER/MOD만 노출)
- **레이아웃**: `ListView` of PENDING reports
  - 행: 신고된 메시지 미리보기(2줄) + 작성자 + 사유 + 신고자 + [무시 / 메시지 삭제 / 사용자 ban] 버튼
- **상태 필터** Tab: 진행중 / 처리됨

### 8.4 모바일 진입점 / 사이드바
- 모바일 웹은 사이드바를 풀스크린 drawer로 (현재 패턴 유지)
- 멤버 N명 영역을 명시적으로 탭 가능하게 (`InkWell` + 시각적 affordance)
- 강퇴/ban 알림 (`/user/queue/kicked` 수신) → SnackBar + `context.go('/chat')`로 라우터 푸시 → 갇히지 않게

### 8.5 Riverpod 프로바이더
- `roomMembersProvider(roomId)` — REST + STOMP 구독 통합 StateNotifier
- `roomBansProvider(roomId)` — REST 전용 (자주 바뀌지 않음)
- `roomReportsProvider(roomId, status)` — REST 폴링 (또는 새 신고 STOMP 이벤트 시 invalidate)
- `currentRoomRoleProvider(roomId)` — 현재 사용자 역할 (UI 분기용)

### 8.6 라우터
- 신규 라우트 없음 (다이얼로그/시트만)
- 강퇴 시 `context.go('/chat')`로 폴백

---

## 9. Tests

### 9.1 백엔드 단위
- `RoomPermissionServiceTest` — 매트릭스 모든 셀 (OWNER/MOD/MEMBER × 액션 9종)
- `MemberManagementServiceTest` — 강퇴/뮤트/위임 happy path + 거부 케이스
- `RoomBanServiceTest` — ban → kick 트랜잭션, 중복 ban 멱등성
- `MessageReportServiceTest` — 자기 메시지 신고 거부, 상태 전이

### 9.2 백엔드 통합 (MockMvc)
- 모든 REST 엔드포인트 — 200/201/204 happy + 403/404/400 거부
- DM 방 차단 케이스
- OWNER 자기 강퇴 거부

### 9.3 백엔드 STOMP 통합
- 강퇴 시 `/user/queue/kicked` 수신 검증
- ban 시 재입장 시도 → `ROOM_BANNED` broadcast 검증

### 9.4 프론트엔드 위젯
- 멤버 다이얼로그 — 역할별 액션 메뉴 분기
- 신고 다이얼로그 — 자기 메시지 신고 메뉴 비표시
- 모더레이터 큐 — OWNER/MOD만 ⚙ 진입점 노출
- 강퇴 수신 시 라우터 푸시

### 9.5 E2E 시나리오 (수동 또는 통합)
1. OWNER가 MEMBER A를 모더레이터로 위임 → A의 액션 메뉴에 강퇴 노출됨
2. MOD A가 MEMBER B를 강퇴 → B는 즉시 라우터 빠짐 → 사이드바에서 같은 방 다시 진입 시도 → ROOM_FULL/없음 표시
3. MOD A가 MEMBER B를 ban → B는 강퇴 + 재입장 시 ROOM_BANNED 에러
4. MEMBER C가 메시지 신고 → MOD A의 모더레이터 큐에 PENDING 표시 → A가 메시지 삭제 → 큐에서 RESOLVED로 이동
5. OWNER가 모바일 웹에서 사이드바 → 멤버 시트 → 강퇴 → 시트 닫힘 + 토스트

---

## 10. Migration / Rollout

### 10.1 단일 V6 마이그레이션
- 위 SQL 그대로 적용 (room_members 컬럼 추가 + 백필 + 두 신규 테이블)
- Flyway가 자동 실행 — 별도 백필 스크립트 불필요

### 10.2 기존 데이터 호환성
- `chat_rooms.created_by` 컬럼은 유지 (감사용)
- 기존 `RoomMemberEntity`로 join하던 코드 영향 없음 (default `MEMBER`)
- DM 방의 두 멤버 모두 `MEMBER`로 남음 (DM은 권한 무효)

### 10.3 배포
- chat-service 이미지 재빌드 → K3s ctr import → Helm upgrade
- frontend Flutter 빌드 → 이미지 재빌드 → import → upgrade
- Cloudflare 캐시 퍼지 (frontend 변경 시)
- Helm chart deps 변경 시 `rm charts/*.tgz && helm dependency update`

### 10.4 롤백 계획
- V6 마이그레이션 실패 시 Flyway baseline 롤백
- 코드 롤백: 이전 chat-service/frontend 이미지로 Helm rollback
- 신규 테이블/컬럼은 DROP 가능 (빈 DDL이라 안전)

---

## 11. 판단 근거 (Decision Record)

| 결정 | 대안 | 채택 이유 |
|---|---|---|
| OWNER 1명 단일성 | 다중 OWNER 허용 | 권한 충돌 단순화. Slack/Discord도 단일 owner 모델 일반적 |
| ban = kick + 재입장 차단 통합 | ban과 kick 분리 | UI 혼란 방지, "한 번에 끝내고 싶은 상황"이 일반적 |
| 신고 큐는 방별 | 전역 admin 큐 | Phase 1은 셀프-운영 모델, 전역 admin은 추후 |
| DM 운영 비활성 | DM에도 차단 기능 | DM은 양쪽 합의 입장, 차단은 상위 레벨(사용자 차단)에서 다뤄야 함 — Phase 추후 |
| 뮤트 5/30/60분 프리셋 | 커스텀 입력 | UX 단순화, Phase 2에서 확장 |
| 단일 V6 마이그레이션 | V6/V7/V8 분할 | 한 번에 일관 상태로 진입, 부분 적용 위험 회피 |
| ban 게이트는 join 시점 | 메시지 발송 시점 | 가시성: ban된 사람이 방에 들어와서 보고 있는 상태 자체가 부적절 |

---

## 12. Open Questions / Risks

1. **모더레이터 위임 알림** — 위임받은 사용자에게 STOMP 알림을 보낼 것인가? (Phase 1: 다음 멤버 리스트 갱신으로만 노출, 명시적 토스트는 Phase 2)
2. **ban 사유 노출** — banned된 사용자 본인에게 사유를 보여줄 것인가? (Phase 1: 사유는 모더레이터 큐 내부에만, 본인 메시지는 "차단되었습니다"만)
3. **신고 자동 처리** — 동일 메시지 N건 이상 신고 시 자동 hide? (Phase 1: 안 함, 수동 처리만)
4. **OWNER 떠나기** — 방을 나가려는 OWNER는? (Phase 1: 방 삭제 또는 다른 사람에게 위임 후 나가기로 강제 — UI 가드)

---

## 13. Success Criteria

- [ ] 권한 매트릭스 9개 액션 × 3개 역할 = 27개 케이스 모두 백엔드 단위 테스트 통과
- [ ] V6 마이그레이션이 기존 데이터를 망가뜨리지 않고 적용 (백업 → 적용 → 검증)
- [ ] OWNER가 모바일 웹에서 멤버 강퇴 → 강퇴된 사용자가 즉시 방에서 빠짐 (E2E 수동 검증)
- [ ] ban된 사용자가 같은 방 재입장 시도 → `ROOM_BANNED` 에러로 차단됨 (E2E)
- [ ] 신고 → 모더레이터 큐 → 처리 → 큐에서 사라짐 (E2E)
- [ ] DM 방에 권한 API 호출 시 400 거부 (회귀 방지)
- [ ] 사이드바 드로어/모달이 모바일 웹 + 데스크톱 웹에서 자연스럽게 동작 (육안 검증)
- [ ] CI green (`./gradlew test` + `flutter test`)

---

## 14. Out-of-band Considerations

- **보안**: 모든 권한 체크는 서버 사이드에서 — 프론트엔드 분기는 UX용일 뿐
- **JWT**: 기존 GATEWAY_INTERNAL_SECRET 패턴 그대로 사용, Authorization 헤더에서 userId 추출
- **로깅**: 모든 운영 액션 (강퇴/ban/위임/신고처리)은 `AuditService`로 기록
- **Rate limit**: 신고는 사용자당 분당 5건 제한 (악용 방지) — 기존 rate limit 패턴 있으면 활용, 없으면 Phase 2로 미룸
- **i18n**: Phase 1은 한국어 하드코딩 OK (현재 패턴)

---

## End of Spec
