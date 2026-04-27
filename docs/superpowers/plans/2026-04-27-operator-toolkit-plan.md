# Operator Toolkit (Phase 1) — Implementation Plan

- **Date**: 2026-04-27
- **Spec**: `docs/superpowers/specs/2026-04-27-operator-toolkit-design.md`
- **Branch**: `develop` (subagent-driven 실행 중 분기 가능)
- **Total Tasks**: 8 (5 backend + 3 frontend)
- **Workflow**: subagent-driven-development (implementer → spec reviewer → quality reviewer)

---

## Task ordering / dependency graph

```
T1 (DB+엔티티+레포)
  ↓
T2 (Permission/Member/Ban services)
  ↓
T3 (Report service)
  ↓
T4 (Game gates: ban join + mute send + STOMP events)
  ↓
T5 (REST controllers + MockMvc)
  ↓
T6 (Frontend models + Riverpod providers)  ← T1 schema 안정 후
  ↓
T7 (Frontend UI: 멤버 시트 + 신고 다이얼로그)
  ↓
T8 (Frontend UI: 모더레이터 큐 + STOMP 수신 + 사이드바 진입점)
```

Backend는 T1→T5 순서 엄격, frontend는 T6→T8 순서 엄격, 동일 사이클 내 backend/frontend 병렬은 하지 않는다 (스키마 충돌 회피).

---

## Task 1: V6 마이그레이션 + JPA 엔티티 + 레포지토리

### 컨텍스트
spec §4 (데이터 모델) 그대로 구현. 기존 `RoomMemberEntity` (chat-service/src/main/java/com/chatflow/chat/entity/RoomMemberEntity.java)에 컬럼 두 개 추가, 신규 엔티티 두 개 + 레포지토리 두 개 생성. Flyway 마이그레이션 V6 추가.

### Files to create
1. `chat-service/src/main/resources/db/migration/V6__operator_toolkit.sql` — spec §4.1 SQL 그대로
2. `chat-service/src/main/java/com/chatflow/chat/entity/RoomRole.java` — enum `OWNER, MODERATOR, MEMBER`
3. `chat-service/src/main/java/com/chatflow/chat/entity/RoomBanEntity.java` — `@IdClass(RoomBanId.class)` 복합 PK
4. `chat-service/src/main/java/com/chatflow/chat/entity/RoomBanId.java` — `(roomId, userId)` Serializable
5. `chat-service/src/main/java/com/chatflow/chat/entity/MessageReportEntity.java` — Long PK + ReportReason/ReportStatus enum
6. `chat-service/src/main/java/com/chatflow/chat/entity/ReportReason.java` — `SPAM, HARASSMENT, INAPPROPRIATE, OTHER`
7. `chat-service/src/main/java/com/chatflow/chat/entity/ReportStatus.java` — `PENDING, RESOLVED, DISMISSED`
8. `chat-service/src/main/java/com/chatflow/chat/repository/RoomBanRepository.java` — `existsByRoomIdAndUserId`, `findByRoomId`, `deleteByRoomIdAndUserId`
9. `chat-service/src/main/java/com/chatflow/chat/repository/MessageReportRepository.java` — `findByRoomIdAndStatusOrderByCreatedAtDesc`, `existsByMessageIdAndReportedBy`, `countByReportedByAndCreatedAtAfter` (rate limit용)

### Files to modify
1. `chat-service/src/main/java/com/chatflow/chat/entity/RoomMemberEntity.java` — `RoomRole role` (default MEMBER), `LocalDateTime mutedUntil` 필드 추가. Lombok 패턴 유지.

### Tests
- `chat-service/src/test/java/com/chatflow/chat/repository/RoomBanRepositoryTest.java` — JPA slice (`@DataJpaTest`), 기본 CRUD + existsByRoomIdAndUserId
- `chat-service/src/test/java/com/chatflow/chat/repository/MessageReportRepositoryTest.java` — JPA slice, status 필터링 + rate limit 카운트
- `chat-service/src/test/java/com/chatflow/chat/repository/RoomMemberRepositoryRoleTest.java` — role/mutedUntil 컬럼 read/write

### Acceptance
- `./gradlew :chat-service:test --tests "*Repository*"` green
- Flyway가 V6 마이그레이션을 H2(테스트)에 적용 성공
- `RoomMemberEntity.getRole()` 기본값이 `MEMBER`

### Out of scope
- 비즈니스 로직 (T2~T4에서 다룸)
- REST 컨트롤러 (T5에서 다룸)

---

## Task 2: RoomPermissionService + MemberManagementService + RoomBanService

### 컨텍스트
spec §5 (권한 매트릭스) + §6.1, §6.2 비즈니스 로직. 강퇴/뮤트/위임/ban의 trans-actional integrity가 핵심. T1의 엔티티/레포 사용.

### Files to create
1. `chat-service/src/main/java/com/chatflow/chat/service/RoomPermissionService.java`
   - `requireRole(String roomId, String userId, RoomRole... allowed)` — 부족 시 `PermissionDeniedException`
   - `getUserRole(String roomId, String userId): RoomRole`
   - `requireNotDmRoom(String roomId)` — DM이면 `RoomTypeNotSupportedException`
2. `chat-service/src/main/java/com/chatflow/chat/service/MemberManagementService.java`
   - `kickMember(roomId, actorUserId, targetUserId)` — RoomMemberRepository.delete + STOMP `/user/queue/kicked` + `/topic/chat/{roomId}/members` broadcast
   - `muteMember(roomId, actorUserId, targetUserId, minutes)` — mutedUntil 업데이트 + STOMP `/user/queue/muted` + `/topic/.../members` broadcast
   - `unmuteMember(...)` — mutedUntil = null
   - `changeRole(roomId, ownerUserId, targetUserId, newRole)` — OWNER만 호출, 위임 시 기존 OWNER 자동 MOD 강등 (단일 트랜잭션)
3. `chat-service/src/main/java/com/chatflow/chat/service/RoomBanService.java`
   - `banUser(roomId, actorUserId, targetUserId, reason)` — kick + insert ban (단일 트랜잭션)
   - `unbanUser(...)`
   - `isBanned(roomId, userId): boolean`
   - `listBans(roomId): List<RoomBanEntity>`
4. `chat-service/src/main/java/com/chatflow/chat/exception/PermissionDeniedException.java` — RuntimeException
5. `chat-service/src/main/java/com/chatflow/chat/exception/RoomTypeNotSupportedException.java` — RuntimeException
6. `chat-service/src/main/java/com/chatflow/chat/exception/SelfTargetNotAllowedException.java` — RuntimeException

### Files to modify
- 없음 (서비스만 신규 추가)

### Tests
- `chat-service/src/test/java/com/chatflow/chat/service/RoomPermissionServiceTest.java` — 매트릭스 9액션 × 3역할 = 27 케이스 (`@SpringBootTest` 또는 mock 기반)
- `chat-service/src/test/java/com/chatflow/chat/service/MemberManagementServiceTest.java` — kick/mute/unmute/changeRole happy + 거부 (OWNER 자기 강퇴, MOD가 OWNER 강퇴, 일반 멤버 강퇴 시도)
- `chat-service/src/test/java/com/chatflow/chat/service/RoomBanServiceTest.java` — ban (kick+insert 트랜잭션), 중복 ban 멱등, isBanned

### Acceptance
- `./gradlew :chat-service:test --tests "RoomPermissionServiceTest"` green
- `./gradlew :chat-service:test --tests "MemberManagementServiceTest"` green
- `./gradlew :chat-service:test --tests "RoomBanServiceTest"` green
- OWNER 위임 시 기존 OWNER가 MOD로 자동 강등됨 (테스트 검증)

### Out of scope
- HTTP 레이어 (T5)
- 메시지 발송 시 mute 체크 (T4)
- join 시 ban 체크 (T4)

---

## Task 3: MessageReportService

### 컨텍스트
spec §6.3 신고 흐름. 자기 메시지 신고 거부 + rate limit (분당 5건) 룰. 메시지 ID로 ChatMessageEntity 조회해 작성자 비교.

### Files to create
1. `chat-service/src/main/java/com/chatflow/chat/service/MessageReportService.java`
   - `submitReport(messageId, reporterUserId, reason, comment): Long` — 자기 메시지 신고 / rate limit 거부
   - `listPendingReports(roomId): List<ReportDto>` — message snippet + author 포함하도록 조인
   - `updateStatus(reportId, actorUserId, ReportStatus)` — 권한 체크 (OWNER/MOD)
2. `chat-service/src/main/java/com/chatflow/chat/dto/ReportDto.java` — id, messageId, messageContent (200자 truncated), messageAuthor, reportedBy, reason, comment, status, createdAt
3. `chat-service/src/main/java/com/chatflow/chat/exception/SelfReportNotAllowedException.java`
4. `chat-service/src/main/java/com/chatflow/chat/exception/ReportRateLimitException.java`

### Files to modify
- 없음

### Tests
- `chat-service/src/test/java/com/chatflow/chat/service/MessageReportServiceTest.java` — 자기 메시지 거부, rate limit (분당 5건 초과 거부), 권한 없는 사용자가 updateStatus 시 거부, listPendingReports 페이로드 검증

### Acceptance
- `./gradlew :chat-service:test --tests "MessageReportServiceTest"` green
- 동일 사용자가 같은 메시지 신고는 멱등 (이미 신고된 경우 200 idempotent — 또는 409 — 한 가지 선택해 일관성 유지. 결정: 멱등 200으로 처리)

### Out of scope
- HTTP (T5)
- 신고 도착 시 STOMP 알림 (Phase 2)

---

## Task 4: Game gates + STOMP 이벤트 통합

### 컨텍스트
spec §3.2 (게이트 흐름), §7 (STOMP 이벤트). `UserPresenceService.join`에 ban 게이트, `MessageSenderService` (또는 `ChatService.sendMessage`)에 mute 게이트. `MemberManagementService` / `RoomBanService`에서 `/user/queue/kicked|muted` + `/topic/chat/{roomId}/members` 발송 통합.

### Files to modify
1. `chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java`
   - `join` 메서드 진입 직후, 만석 분기보다 먼저:
     ```java
     if (roomBanService.isBanned(message.getChatRoomId(), currentUserId)) {
         messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/errors",
             Map.of("type", "ROOM_BANNED", "roomId", roomId));
         return;
     }
     ```
   - 의존성에 `RoomBanService` 추가 (생성자 주입)
2. `chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java` (또는 호출 진입점)
   - `send` 진입 시 RoomMemberEntity.mutedUntil 체크 → muted면 `MutedException` throw + 사용자에게 `/user/queue/errors` 응답
   - 기존 발송 로직 보존
3. `chat-service/src/main/java/com/chatflow/chat/service/MemberManagementService.java` (T2에서 생성됨)
   - kick/mute/changeRole 시 `/topic/chat/{roomId}/members` payload는 spec §7.1 형식 (현재 멤버 + role + mutedUntil 포함)
   - kick 시 `convertAndSendToUser(targetUserId, "/queue/kicked", ...)`
   - mute 시 `convertAndSendToUser(targetUserId, "/queue/muted", { roomId, mutedUntil, by })`

### Files to create
- `chat-service/src/main/java/com/chatflow/chat/exception/MutedException.java`

### Tests
- `chat-service/src/test/java/com/chatflow/chat/service/UserPresenceServiceBanGateTest.java` — banned 사용자 join → ROOM_BANNED broadcast 확인 (만석 분기에 도달 X)
- `chat-service/src/test/java/com/chatflow/chat/service/MessageSenderServiceMuteGateTest.java` — muted 사용자 send → MutedException
- `chat-service/src/test/java/com/chatflow/chat/service/MemberManagementServiceStompTest.java` — kick 시 /user/queue/kicked + /topic/.../members 이벤트 발송 검증 (SimpMessagingTemplate mock)

### Acceptance
- 위 3개 테스트 green
- 기존 `UserPresenceServiceTest` (있다면) 회귀 없음
- DM 방 ban API 호출은 T5에서 컨트롤러 레벨 거부 (여기서는 T2 RoomBanService.banUser가 RoomPermissionService.requireNotDmRoom 호출)

### Out of scope
- 메시지 mute STOMP queue 클라이언트 처리 (T8)
- ban broadcast UI 수신 (이미 `/topic/chat/{roomId}/errors` 핸들러 존재 — T8에서 ROOM_BANNED 케이스 추가)

---

## Task 5: REST 컨트롤러 + MockMvc 통합 테스트

### 컨텍스트
spec §6 모든 엔드포인트 (멤버 / ban / 신고 = 9개). 기존 `ChatRoomController`와 별도로 새 컨트롤러 분리.

### Files to create
1. `chat-service/src/main/java/com/chatflow/chat/controller/RoomMemberManagementController.java`
   - `@RequestMapping("/api/chat/rooms/{roomId}")`
   - GET `/members`
   - PATCH `/members/{userId}/role`
   - DELETE `/members/{userId}`
   - POST/DELETE `/members/{userId}/mute`
2. `chat-service/src/main/java/com/chatflow/chat/controller/RoomBanController.java`
   - `@RequestMapping("/api/chat/rooms/{roomId}/bans")`
   - GET / POST / DELETE `/{userId}`
3. `chat-service/src/main/java/com/chatflow/chat/controller/MessageReportController.java`
   - POST `/api/chat/messages/{messageId}/reports`
   - GET `/api/chat/rooms/{roomId}/reports`
   - PATCH `/api/chat/reports/{reportId}`
4. DTOs (request/response):
   - `MemberDto`, `RoleChangeRequest`, `MuteRequest`, `BanRequest`, `ReportSubmitRequest`, `ReportStatusUpdateRequest`
5. `chat-service/src/main/java/com/chatflow/chat/exception/GlobalExceptionHandler.java` 확장 또는 신규:
   - `PermissionDeniedException → 403`
   - `RoomTypeNotSupportedException → 400`
   - `SelfTargetNotAllowedException → 400`
   - `SelfReportNotAllowedException → 400`
   - `ReportRateLimitException → 429`
   - `MutedException → 423 Locked` (또는 400 — 결정: 423 유지)
   - 기존 핸들러가 있으면 통합

### Files to modify
- 없음 (기존 컨트롤러 변경 X)

### Tests
- `chat-service/src/test/java/com/chatflow/chat/controller/RoomMemberManagementControllerTest.java` — `@WebMvcTest` 또는 `@SpringBootTest` MockMvc, 9개 엔드포인트 happy + 권한 거부 (403) + DM 거부 (400) + self-target (400)
- `chat-service/src/test/java/com/chatflow/chat/controller/MessageReportControllerTest.java` — 신고 happy + 자기 메시지 (400) + rate limit (429) + 큐 조회 권한 (403)

### Acceptance
- `./gradlew :chat-service:test --tests "*Controller*"` green
- 모든 9개 엔드포인트가 spec §6 의 status code / body 형식과 일치
- DM 방 호출 시 모든 운영 엔드포인트가 400 거부

### Out of scope
- 프론트엔드 통합 (T6~T8)

---

## Task 6: Frontend models + Riverpod providers

### 컨텍스트
spec §8.5 프로바이더. 기존 `RoomMember` 모델은 별도로 없는 것 같으므로 (`chat_room.dart`만 존재) 신규 작성. JSON serialization (수동 fromJson/toJson — 이 프로젝트는 build_runner 사용 X 패턴 추정, 기존 chat_message.dart 따라 결정). Dio 클라이언트는 기존 패턴 사용.

### Files to create
1. `frontend/lib/shared/models/room_member.dart` — `userId`, `username`, `role` (RoomRole enum), `mutedUntil` (DateTime?). fromJson/toJson.
2. `frontend/lib/shared/models/room_role.dart` — `enum RoomRole { owner, moderator, member }` + `RoomRoleX.fromString` / `asString`
3. `frontend/lib/shared/models/room_ban.dart` — `userId, username, bannedBy, reason, bannedAt`
4. `frontend/lib/shared/models/message_report.dart` — id, messageId, messageContent, messageAuthor, reportedBy, reason, comment, status, createdAt
5. `frontend/lib/features/chat/admin/room_admin_api.dart` — Dio 기반 클라이언트 (모든 9 엔드포인트 메서드)
6. `frontend/lib/features/chat/admin/room_members_provider.dart` — `StateNotifierProvider.family<RoomMembersNotifier, AsyncValue<List<RoomMember>>, String roomId>`
   - `fetch()` REST → set
   - `applyStompUpdate(payload)` — STOMP /topic/chat/{roomId}/members 수신 시 호출
7. `frontend/lib/features/chat/admin/room_bans_provider.dart` — REST only
8. `frontend/lib/features/chat/admin/room_reports_provider.dart` — REST + status 필터
9. `frontend/lib/features/chat/admin/current_room_role_provider.dart` — `Provider.family` derived from roomMembersProvider + authProvider.user.userId

### Files to modify
- `frontend/lib/core/network/stomp_service.dart` — `/topic/chat/{roomId}/members` 구독 추가, `/user/queue/kicked` 구독 추가, `/user/queue/muted` 구독 추가. `MemberListCallback`, `KickedCallback`, `MutedCallback` 콜백 시그니처 추가.

### Tests
- `frontend/test/features/chat/admin/room_members_provider_test.dart` — fetch happy + STOMP update merge
- `frontend/test/shared/models/room_member_test.dart` — JSON round-trip

### Acceptance
- `flutter test test/features/chat/admin/` green
- `flutter analyze` 경고 없음
- STOMP 구독 추가가 기존 메시지/타이핑/프레즌스 구독을 깨뜨리지 않음

### Out of scope
- UI 위젯 (T7, T8)

---

## Task 7: Frontend UI — 멤버 관리 시트 + 메시지 신고 다이얼로그

### 컨텍스트
spec §8.1, §8.2. 모바일 풀스크린 바텀시트 / 데스크톱 모달 분기는 `MediaQuery.of(context).size.width < 600`. 메시지 버블의 long-press / context menu는 기존 `chat_messages_list.dart` 패턴 따라.

### Files to create
1. `frontend/lib/features/chat/admin/widgets/room_members_sheet.dart`
   - 함수 `showRoomMembersSheet(BuildContext, String roomId)` — 모바일은 `showModalBottomSheet(isScrollControlled: true)`, 데스크톱은 `showDialog`
   - 내부: `Consumer` → `roomMembersProvider(roomId)` + `currentRoomRoleProvider(roomId)`
   - 행: avatar + name + role badge + ⋯ menu (역할별 분기)
   - 액션 onTap → API call (Dio) → 토스트 (성공/에러)
   - 강퇴/ban은 `AlertDialog` 확인 단계
2. `frontend/lib/features/chat/admin/widgets/role_badge.dart` — small chip (OWNER 금색, MOD 보라)
3. `frontend/lib/features/chat/admin/widgets/message_report_dialog.dart`
   - 함수 `showMessageReportDialog(BuildContext, String messageId)`
   - 폼: ChoiceChip (4개 사유) + TextField (코멘트, 500자)
   - 제출 → API → 토스트

### Files to modify
1. `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`
   - 방 헤더 "멤버 N명" 영역을 `InkWell`로 감싸 → onTap → `showRoomMembersSheet`
2. `frontend/lib/features/chat/widgets/chat_messages_list.dart`
   - 메시지 버블 long-press / 우클릭 메뉴에 "신고" 항목 추가 (자기 메시지일 때 비표시)
   - onTap → `showMessageReportDialog`

### Tests
- `frontend/test/features/chat/admin/widgets/room_members_sheet_test.dart` — OWNER/MOD/MEMBER 시점별 액션 메뉴 분기
- `frontend/test/features/chat/admin/widgets/message_report_dialog_test.dart` — 사유 선택 + 제출 호출 검증

### Acceptance
- `flutter test test/features/chat/admin/widgets/` green
- `flutter analyze` 경고 없음
- 데스크톱/모바일 분기 시각 확인 (육안 — 이 task는 자동 검증 못 하므로 implementer가 description에 시각 검증 단계 명시)

### Out of scope
- 모더레이터 큐 (T8)
- STOMP /user/queue 수신 (T8)

---

## Task 8: Frontend UI — 모더레이터 큐 + STOMP 수신 + 사이드바 진입점

### 컨텍스트
spec §8.3, §8.4. 모더레이터 큐는 별도 시트/페이지. STOMP `/user/queue/kicked|muted` 수신 → 라우터 푸시 / 입력창 비활성. ROOM_BANNED 에러 broadcast 처리.

### Files to create
1. `frontend/lib/features/chat/admin/widgets/moderator_queue_sheet.dart`
   - 함수 `showModeratorQueueSheet(BuildContext, String roomId)`
   - Consumer → `roomReportsProvider(roomId, ReportStatus.pending)`
   - 리스트: 메시지 미리보기 + 사유 + 신고자 + [무시 / 메시지 삭제 / 사용자 ban]
   - 각 액션 → API → 토스트 + provider invalidate
2. `frontend/lib/features/chat/admin/admin_event_listener.dart`
   - StatefulWidget 또는 Riverpod listener — `stomp_service.dart`의 `KickedCallback`, `MutedCallback`, ROOM_BANNED 콜백을 받아:
     - kicked: `context.go('/chat')` + SnackBar "강퇴되었습니다"
     - muted: `mutedUntilProvider(roomId).state = mutedUntil`로 입력창 비활성화 신호
     - banned: `context.go('/chat')` + SnackBar "차단되었습니다"

### Files to modify
1. `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`
   - 방 헤더 ⚙ 버튼 추가 — OWNER/MOD에게만 노출 (`currentRoomRoleProvider` 기반) → `showModeratorQueueSheet`
2. `frontend/lib/features/chat/widgets/chat_input.dart`
   - `mutedUntilProvider(roomId)` watch → muted 상태면 입력창 disabled + "음소거됨 (만료: HH:mm)" 안내
3. `frontend/lib/features/chat/chat_page.dart`
   - `AdminEventListener` 위젯을 page tree에 삽입
4. `frontend/lib/core/network/stomp_service.dart` (필요 시)
   - `/topic/chat/{roomId}/errors`의 `ROOM_BANNED` 케이스 처리 추가 (T6에서 콜백 시그니처는 추가됨, 여기서 dispatch)

### Tests
- `frontend/test/features/chat/admin/widgets/moderator_queue_sheet_test.dart` — 액션 버튼 클릭 → API mock 호출 검증
- `frontend/test/features/chat/admin/admin_event_listener_test.dart` — kicked 콜백 → router push 검증
- `frontend/test/features/chat/widgets/chat_input_muted_test.dart` — muted 상태 입력창 disabled

### Acceptance
- `flutter test` 전체 green
- `flutter analyze` 경고 없음
- 데스크톱/모바일 시트 둘 다 정상 동작 (육안)
- 강퇴된 사용자가 다시 그 방 진입 시도 → ROOM_BANNED SnackBar + 사이드바로 돌아감

### Out of scope
- 다른 운영 알림 푸시 (Phase 2)

---

## Final Review

8개 task 완료 후:
1. `superpowers:requesting-code-review` 패턴으로 전체 변경 final code-reviewer subagent 호출
2. 통합 QA — 다음 시나리오 수동 검증 (E2E 테스트는 Phase 1 범위 밖):
   - OWNER 위임 → 위임된 MOD가 강퇴 가능
   - MOD 강퇴 → 본인 즉시 라우터 빠짐
   - ban → 재입장 차단
   - 신고 → 모더레이터 큐 → 처리
   - DM 방 운영 API 차단
   - 모바일/데스크톱 시트 둘 다 동작
3. K3s 배포 (chat-service + frontend 이미지 재빌드 → ctr import → Helm upgrade) + Cloudflare 캐시 퍼지

---

## Risk register

| 리스크 | 완화 |
|---|---|
| V6 마이그레이션이 prod 데이터에서 실패 | T1에서 H2로 검증 + prod 백업 후 적용 |
| OWNER 백필이 NULL `created_by` 방에서 NPE | UPDATE에서 IS NOT NULL 가드 (이미 SQL상 안전) |
| STOMP 구독 추가가 기존 구독 콜백을 깨뜨림 | T6에서 회귀 테스트 + T8에서 통합 검증 |
| 모바일 풀스크린 시트가 키보드와 충돌 | T7에서 `MediaQuery.of(context).viewInsets.bottom` 패딩 |
| 권한 검사가 컨트롤러/서비스 양쪽에 흩어져 일관성 깨짐 | RoomPermissionService에 단일화 — 서비스 메서드 진입 시 1번만 호출 |

---

## End of Plan
