# Phase 2A — User Profile + 자투리 — Implementation Plan

- **Date**: 2026-05-04
- **Spec**: `docs/superpowers/specs/2026-05-04-phase2a-profile-design.md`
- **Branch**: `develop`
- **Total Tasks**: 5

---

## Task graph

```
P1 (gateway: schema + entity + repo + ProfileController)
  ↓
P2 (chat-service 자투리: EDIT mute + 1~1440 mute range)
  ↓
P3 (frontend: UserProfile model + profileProvider + UserAvatar 위젯)
  ↓
P4 (frontend: ProfileEditDialog + 사이드바 진입점 + avatar 일관 적용)
  ↓
P5 (frontend: 커스텀 뮤트 시간 다이얼로그 + 멤버시트 통합)
```

---

## Task 1: gateway-service — User profile 엔드포인트

### Files to modify
- `gateway-service/src/main/resources/schema.sql` — append:
  ```sql
  ALTER TABLE users ADD COLUMN IF NOT EXISTS status_message VARCHAR(100);
  ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(300);
  ```
- `gateway-service/src/main/java/com/chatflow/gateway/entity/UserEntity.java` — add `statusMessage`, `bio` fields with `@Column`.
- `gateway-service/src/main/java/com/chatflow/gateway/repository/UserRepository.java` — add `Mono<UserEntity> findByUserId(String userId)`.

### Files to create
- `gateway-service/src/main/java/com/chatflow/gateway/controller/ProfileController.java`
  - `@RestController @RequestMapping("/api/users") @RequiredArgsConstructor`
  - `GET /me` — extracts userId from `X-User-Id` header (or principal — match `RateLimiterConfig` pattern).
  - `PATCH /me` — partial update; null = unchanged; empty string = clear (set NULL).
  - `GET /{userId}` — fetch other user's profile (read-only).
- `gateway-service/src/main/java/com/chatflow/gateway/dto/ProfileResponse.java` (record).
- `gateway-service/src/main/java/com/chatflow/gateway/dto/ProfileUpdateRequest.java` (record).
- `gateway-service/src/test/.../ProfileControllerTest.java` — `@WebFluxTest` + `@MockBean UserRepository`.

### Acceptance
- `./gradlew :gateway-service:test` green
- 검증: statusMessage > 100자 → 400; bio > 300자 → 400; profileImageUrl 빈 문자열 → null 저장.

---

## Task 2: chat-service — EDIT mute 게이트 + 뮤트 범위 1~1440

### Files to modify
- `chat-service/src/main/java/com/chatflow/chat/service/MessageEditService.java`
  - `editMessage` 진입 시 mute 게이트 (T4 `MessageSenderService` 패턴 동일):
    ```java
    RoomMemberEntity member = roomMemberRepository.findByRoomIdAndUserId(...).orElse(null);
    if (member != null && member.getMutedUntil() != null && member.getMutedUntil().isAfter(LocalDateTime.now())) {
        log.warn(...);
        return false;
    }
    ```
- `chat-service/src/main/java/com/chatflow/chat/service/MemberManagementService.java`
  - `ALLOWED_MUTE_MINUTES` 상수 제거. 대신 1~1440 범위 검증:
    ```java
    if (minutes < 1 || minutes > 1440) {
        throw new IllegalArgumentException("Mute minutes must be 1..1440");
    }
    ```
- `chat-service/src/main/java/com/chatflow/chat/controller/RoomMemberManagementController.java`
  - PathVariable / Request body에서 minutes 동일 검증 (사실 서비스에 위임하므로 별도 변경 없을 수도).

### Files to create
- `chat-service/src/test/.../MessageEditServiceMuteGateTest.java`
  - 음소거 활성 → editMessage false + 저장 안 됨
  - 음소거 만료 → 정상

### Files to update (tests)
- `MemberManagementServiceTest`:
  - 기존 invalidMuteMinutes 테스트 케이스 갱신 (0, 1441, -5 IAE; 1, 720, 1440 정상)

### Acceptance
- `./gradlew :chat-service:test` green
- 기존 mute 5/30/60 호출은 호환 (1~1440 안이므로)

---

## Task 3: frontend — UserProfile 모델 + profileProvider + UserAvatar

### Files to create
- `frontend/lib/shared/models/user_profile.dart`
  - 클래스 `UserProfile { String userId, username, role; String? profileImageUrl, statusMessage, bio; }`
  - fromJson / toJson
- `frontend/lib/features/profile/profile_api.dart`
  - `class ProfileApi`, Dio injection
  - `Future<UserProfile> getMe()`
  - `Future<UserProfile> updateMe({String? profileImageUrl, String? statusMessage, String? bio})`
  - `Future<UserProfile> getById(String userId)`
- `frontend/lib/features/profile/profile_provider.dart`
  - `final profileApiProvider = Provider<ProfileApi>(...)`
  - `final profileProvider = StateNotifierProvider<ProfileNotifier, AsyncValue<UserProfile>>` — fetchMe on init
- `frontend/lib/shared/widgets/user_avatar.dart`
  - `class UserAvatar extends StatelessWidget`
  - 파라미터: `String? imageUrl, String fallbackName, double radius (default 18), VoidCallback? onTap`
  - imageUrl 있으면 NetworkImage, 없으면 첫 글자 + AppColors.avatarPalette[hash]
  - onTap 있으면 InkWell wrap

### Tests
- `frontend/test/shared/models/user_profile_test.dart` — JSON round-trip
- `frontend/test/shared/widgets/user_avatar_test.dart` — 둘 다 모드 렌더링

### Acceptance
- `flutter analyze` no error
- `flutter test test/shared/` green

---

## Task 4: frontend — ProfileEditDialog + 사이드바 진입점 + avatar 통합

### Files to create
- `frontend/lib/features/profile/widgets/profile_edit_dialog.dart`
  - `Future<void> showProfileEditDialog(BuildContext context)`
  - 모바일/데스크톱 분기 (showModalBottomSheet vs showDialog)
  - 폼: avatar (변경 버튼 → file_picker → chat-service `/api/chat/files/upload` → URL 받음 → state 갱신), statusMessage TextField (max 100), bio TextField (max 300)
  - 저장 → ProfileApi.updateMe → profileProvider invalidate
- `frontend/lib/features/profile/widgets/profile_preview_dialog.dart`
  - 다른 사용자 프로필 미리보기 (avatar + status + bio + 닫기 버튼)
  - `showProfilePreview(BuildContext, String userId)`

### Files to modify
- `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`
  - 사이드바 하단에 "내 프로필" 영역 추가:
    - `UserAvatar` (size 36) + username + statusMessage 1줄 + InkWell
    - 탭 → showProfileEditDialog
  - 기존 logout 버튼 옆/아래에 통합
- `frontend/lib/features/chat/admin/widgets/room_members_sheet.dart`
  - 멤버 행의 leading `CircleAvatar` → `UserAvatar` 교체 (memberFromList의 profileImageUrl 사용 — 프로바이더 확장 시점에 해결, 일단 fallback)
  - 행 탭 → showProfilePreview(userId)
- `frontend/lib/features/chat/widgets/chat_messages_list.dart`
  - 메시지 버블 avatar (현재 `CircleAvatar` 또는 첫 글자) → `UserAvatar` 교체
  - msg.userId 또는 msg.profileImageUrl이 없으므로 — Phase 2A는 fallback 모드만 (Phase 2B에서 메시지 페이로드에 profileImageUrl 포함 결정)
  - 즉 이 task는 메시지 버블에서 UserAvatar로 컴포넌트 교체만 (이미지 URL은 null로 전달 → fallback letter)

### Tests
- `frontend/test/features/profile/profile_edit_dialog_test.dart` — 검증 + 제출 콜백
- `frontend/test/features/profile/profile_preview_dialog_test.dart` — 다른 사용자 데이터 렌더

### Acceptance
- 사이드바에서 본인 프로필 탭 → 다이얼로그 → 저장 → SnackBar + provider 갱신
- 멤버 시트 행 탭 → 미리보기 다이얼로그
- `flutter analyze` 경고 없음

---

## Task 5: frontend — 커스텀 뮤트 시간 다이얼로그

### Files to create
- `frontend/lib/features/chat/admin/widgets/mute_custom_time_dialog.dart`
  - `Future<int?> showMuteCustomTimeDialog(BuildContext)` — 분 입력 (1~1440), 확인/취소

### Files to modify
- `frontend/lib/features/chat/admin/widgets/room_members_sheet.dart`
  - 액션 메뉴에 `'mute_custom'` 옵션 추가 → "다른 시간..."
  - onSelected에서 'mute_custom' 케이스: showMuteCustomTimeDialog → minutes → muteMember 호출

### Tests
- `frontend/test/features/chat/admin/widgets/mute_custom_time_dialog_test.dart` — 1~1440 범위 검증, 0/1441 거부

### Acceptance
- 멤버 액션 → "다른 시간..." → 입력 12 → mute 12분 적용
- 0 입력 → 에러 메시지 + 닫기 안 됨

---

## Final QA

전체 task 완료 후:
1. `./gradlew test` 전체 green
2. `flutter test` 전체 green
3. 수동 검증:
   - 프로필 변경 → 사이드바 / 메시지 버블 즉시 반영
   - 다른 사용자 미리보기
   - 12분 커스텀 뮤트 → EDIT 거부
4. 배포: gateway → chat-service → frontend 순 + Cloudflare 캐시 퍼지

---

## Risk

| 리스크 | 완화 |
|---|---|
| R2DBC ALTER가 매번 실행돼 startup 지연 | `IF NOT EXISTS`로 idempotent — 비용 미미 |
| Avatar URL 외부 호출 보안 | UI에서 chat-service 업로드만 허용, URL 직접 입력 폼 X (Phase 2A) |
| 메시지 버블 avatar URL 부재 | fallback letter로 graceful degrade — 향후 메시지 페이로드 확장 |
| profileProvider STOMP 미지원 → 다른 탭 stale | Phase 2A는 의도적으로 안 함, refresh 시점 갱신 |

---

## End of Plan
