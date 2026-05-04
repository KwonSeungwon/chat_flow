# Phase 2A — User Profile + 자투리 — Design Spec

- **Date**: 2026-05-04
- **Status**: Draft
- **Phase**: Phase 2 of 3, Cycle A (Profile + 자투리)
- **Owner**: seungwon
- **Goal**: 사용자가 본인을 표현할 수 있는 프로필 (avatar / status message / bio)을 도입하고, Phase 1에서 흘러나온 자투리 (EDIT 패스 mute 게이트, 커스텀 뮤트 시간)를 정리한다.

---

## 1. Problem & Motivation

ChatFlow에는 사용자를 표현하는 수단이 username 한 줄뿐이다. 채팅방에서 누가 누구인지 시각적으로 식별하기 어렵고, 사용자가 자신의 상태/소개를 다른 사람에게 알릴 수단이 없다.

또한 Phase 1에서 의도적으로 미룬 자투리 두 건이 있다:
1. EDIT 패스에 mute 게이트가 없어 — 음소거된 사용자가 기존 메시지를 편집해 콘텐츠를 우회 발신할 수 있다.
2. 뮤트 시간이 5/30/60분 프리셋만 — 운영자가 "12분만"처럼 세밀하게 조정할 수 없다.

이 두 자투리는 백엔드 변경 부담이 작아 Profile 사이클에 합쳐 처리한다.

---

## 2. Scope

### In scope
- **사용자 프로필**:
  - 백엔드: `users` 테이블에 `status_message`, `bio` 컬럼 추가 (`profile_image_url`은 이미 존재)
  - 백엔드: `GET /api/users/me`, `PATCH /api/users/me` (gateway-service)
  - 프론트: `UserProfile` 모델, `profileProvider`, `ProfileEditDialog`
  - 프론트: 멤버 시트 + 메시지 버블 + 사이드바 헤더에 avatar 일관 노출
  - 프론트: 사이드바 하단에 "내 프로필" 진입점
  - 아바타 이미지는 **외부 URL 또는 기존 chat-service `/api/chat/files/upload` 통해 업로드한 URL**을 그대로 사용 (별도 avatar 저장소 만들지 않음)

- **자투리**:
  - chat-service: `MessageEditService.editMessage`에 mute 게이트 (T4 패턴 동일)
  - chat-service: `MemberManagementService.muteMember`의 minutes 검증을 1~1440 범위로 완화 (프리셋 의존성 제거)
  - frontend: `RoomMembersSheet` 액션 메뉴에 "다른 시간..." 옵션 → 분 입력 다이얼로그

### Out of scope (사이클 2B 또는 그 이후)
- 드래그-드롭 + 클립보드 이미지 붙여넣기 (2B)
- 명령 팔레트 Cmd+K (2B)
- PWA 강화 / safe-area / pull-to-refresh (2B)
- 별도 avatar 저장소 / S3 / CDN
- 프로필 검색 (이름/소개 기반)
- 사용자 차단(profile-level), 친구 / DM 거절
- i18n / a11y 패스 (Phase 3)

---

## 3. Architecture

### 3.1 백엔드
- **gateway-service** (R2DBC 리액티브):
  - `UserEntity` 확장: `statusMessage`, `bio` 필드 추가
  - `UserRepository.findByUserId(String userId): Mono<UserEntity>` 추가
  - `ProfileController` 신설 (`@RequestMapping("/api/users")`)
  - `schema.sql`에 idempotent ALTER 추가
- **chat-service** (자투리만):
  - `MessageEditService.editMessage`에 mute 게이트
  - `MemberManagementService.ALLOWED_MUTE_MINUTES` 제거 → 1~1440 범위 검증
  - `MuteRequest`는 그대로 (int minutes — 검증 컨트롤러/서비스 양쪽에서 1~1440)

### 3.2 프론트엔드
- 모델: `frontend/lib/shared/models/user_profile.dart`
- 프로바이더:
  - `profileProvider` (현재 사용자) — `Provider<AsyncValue<UserProfile>>`
  - `userProfileByIdProvider(userId)` — 다른 사용자 조회용 캐시 (Phase 2 후속 — Phase 2A는 본인 프로필만)
- 위젯:
  - `ProfileEditDialog` — avatar 업로드 + 상태/소개 텍스트 폼
  - `UserAvatar` (재사용) — 메시지 버블 / 사이드바 / 멤버 시트 공통
- 통합: `chat_room_sidebar.dart` 하단에 "내 프로필" 진입점

---

## 4. Data Model

### 4.1 `users` 테이블 ALTER (gateway-service `schema.sql`)

```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS status_message VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(300);
```

### 4.2 `UserEntity` 확장

```java
@Column("status_message")
private String statusMessage;

@Column("bio")
private String bio;
```

### 4.3 프론트 `UserProfile`

```dart
class UserProfile {
  final String userId;
  final String username;
  final String role;
  final String? profileImageUrl;
  final String? statusMessage;
  final String? bio;
}
```

---

## 5. REST API (gateway-service)

### 5.1 본인 프로필 조회

```
GET /api/users/me
권한: authenticated (X-User-Id 필수)
Response 200:
{
  "userId": "...",
  "username": "...",
  "role": "NURSE",
  "profileImageUrl": "https://..." | null,
  "statusMessage": "..." | null,
  "bio": "..." | null
}
404: 사용자 없음 (이론상 발생 X — JWT 유효 시 무조건 존재)
```

### 5.2 본인 프로필 부분 수정

```
PATCH /api/users/me
권한: authenticated
Body (모든 필드 optional, null이면 그대로 유지, 빈 문자열이면 NULL로 비움):
{
  "profileImageUrl": "https://..." | null,
  "statusMessage": "..." | null,
  "bio": "..." | null
}

검증:
  - statusMessage <= 100자
  - bio <= 300자
  - profileImageUrl <= 512자 + URL 형식 (간단한 http/https prefix 검증)

Response 200: 갱신된 프로필 전체
400: 검증 실패
```

> **결정**: PATCH semantic — `null` = 유지, 빈 문자열 `""` = 명시적으로 비우기 (NULL로 저장). 클라이언트가 "비우기"를 표현할 수 있어야 하므로.

### 5.3 자투리 — chat-service mute 변경

기존:
```
POST /api/chat/rooms/{roomId}/members/{userId}/mute
Body: { minutes: 5 | 30 | 60 }
```

변경 후:
```
Body: { minutes: 1..1440 }  // 1분 ~ 24시간
```

REST 컨트롤러와 `MemberManagementService.muteMember` 양쪽에서 검증.

---

## 6. Frontend UI

### 6.1 프로필 다이얼로그
- **트리거**: 사이드바 하단 "내 프로필" 영역 클릭 (avatar + username 표시 + 호버 시 강조)
- **다이얼로그 구성**:
  - 상단: 큰 avatar (지름 80) + 변경 버튼 (file picker → chat-service 업로드 → URL 저장)
  - 입력 1: 상태 메시지 (단문, 최대 100자)
  - 입력 2: 한 줄 소개 (긴 텍스트 영역, 최대 300자)
  - 저장 / 취소 버튼
- 저장 → PATCH /api/users/me → profileProvider invalidate

### 6.2 Avatar 일관 노출
- **`UserAvatar` 위젯** — `String? imageUrl, String fallbackName, double radius` 받아 동일하게 렌더
  - imageUrl 있으면 NetworkImage
  - 없으면 첫 글자 + 색상 (`AppColors.avatarPalette` 활용)
- 적용 위치:
  - 메시지 버블 (`chat_messages_list.dart`)
  - 멤버 시트 (`room_members_sheet.dart`)
  - 사이드바 방 타일 (DM 상대방 avatar)
  - 사이드바 하단 본인 진입점

### 6.3 다른 사용자 프로필 노출 (조회만)
- 멤버 시트 행에서 사용자 이름 또는 avatar 탭 → 작은 프로필 미리보기 다이얼로그 (avatar + 상태 + 소개, 닫기 버튼만)
- Phase 2A는 GET-only — 다른 사용자 프로필 수정은 권한 없음
- 백엔드에 **GET /api/users/{userId}** 엔드포인트 추가 (인증된 사용자 누구나 조회 가능)

### 6.4 자투리 — 커스텀 뮤트 시간
- `RoomMembersSheet` 액션 메뉴에 "다른 시간..." 추가
- 클릭 → 작은 다이얼로그 — 분 입력 (NumberFormatter, 1~1440)
- 확인 시 muteMember(minutes=입력값)

---

## 7. Permission Matrix (변경 없음)

이 사이클은 권한 모델 변경 없음. 기존 OWNER/MOD/MEMBER 그대로.

---

## 8. Tests

### 8.1 백엔드 단위
- `gateway-service/ProfileControllerTest`:
  - GET /me 인증된 사용자 → 200 + 본인 프로필
  - GET /me 미인증 → 401 (Spring Security 기본)
  - PATCH /me with valid body → 200 + 갱신 반영
  - PATCH /me 검증 실패 (statusMessage > 100자) → 400
  - PATCH /me 빈 문자열 → NULL 저장 확인
- `chat-service/MessageEditServiceMuteGateTest`:
  - 음소거된 사용자가 editMessage → false 반환 + 변경 없음
  - 음소거 만료 사용자가 editMessage → 정상 동작
- `chat-service/MemberManagementServiceTest` 갱신:
  - mute 1분, 720분, 1440분 → 정상
  - mute 0분, 1441분, -5분 → IllegalArgumentException

### 8.2 프론트엔드 위젯 테스트
- `UserAvatar` — imageUrl 있을 때/없을 때 fallback letter
- `ProfileEditDialog` — 입력 검증 + 저장 콜백
- `MuteCustomTimeDialog` — 1~1440 범위 검증

### 8.3 E2E 시나리오 (수동)
1. 본인 프로필 변경 → 메시지 버블 / 사이드바에 새 avatar 즉시 반영
2. 다른 사용자 메시지 버블 avatar 클릭 → 프로필 미리보기
3. OWNER가 멤버 12분 음소거 → 시간 만료 후 해제 자동
4. 음소거 사용자가 EDIT 시도 → 거부

---

## 9. Migration / Rollout

### 9.1 schema.sql 변경
gateway-service `schema.sql`은 R2DBC `spring.sql.init` 모드로 시작 시 실행. `IF NOT EXISTS` 모든 ALTER에 적용 → 멱등.

### 9.2 chat-service mute 검증 변경
기존 5/30/60 클라이언트 호출은 1~1440 범위 안이므로 호환. 새 클라이언트만 커스텀 시간 사용.

### 9.3 배포 순서
1. gateway-service 이미지 재빌드 + 배포
2. chat-service 이미지 재빌드 + 배포
3. frontend 이미지 재빌드 + 배포
4. Cloudflare 캐시 퍼지

---

## 10. 판단 근거 (Decision Record)

| 결정 | 대안 | 채택 이유 |
|---|---|---|
| Avatar는 외부 URL 그대로 저장 | 별도 avatar 저장소 신설 | 기존 chat-service 파일 업로드 API 재활용 — Phase 2A 부담 최소 |
| PATCH semantic — `null` 유지 / `""` 비우기 | PUT 전체 교체 | partial update가 더 자연스러움, PUT은 클라이언트 부담 |
| `users` 스키마 변경은 schema.sql ALTER IF NOT EXISTS | Flyway 도입 | gateway-service는 schema.sql 패턴 일관 유지 |
| 다른 사용자 프로필도 GET만 노출 | 검색/조회 제한 | 채팅 멤버라면 누구든 볼 수 있다는 social UX 일반 |
| mute 1~1440분 | 무제한 | 1년 mute 같은 abuse 방지 |
| 자투리(EDIT mute, 커스텀 시간)는 본 사이클에 합침 | Phase 3로 미룸 | 작은 변경, P1 마무리 의미 있음 |

---

## 11. Open Questions / Risks

1. **Avatar URL 검증** — 외부 URL을 그대로 받으면 SSRF 위험은 없지만, 악성 URL/이미지를 다른 사용자에게 노출시키는 위험은 있다. Phase 2A는 client-side에서 URL 입력이 아니라 chat-service 업로드만 허용 (UI 강제). 직접 URL 텍스트 입력은 향후 폼.
2. **R2DBC + JSR 검증** — gateway-service에서 Bean Validation 사용 가능한지 확인 (`@Valid`, `@Size`, `@Pattern`). 없으면 수동 검증.
3. **profileProvider invalidation** — STOMP 이벤트로 본인 프로필 변경 broadcast해야 하는지? Phase 2A는 안 함 (다른 탭에서는 새로고침 시점에 갱신).

---

## 12. Success Criteria

- [ ] 사용자가 사이드바 하단 "내 프로필" 클릭 → 다이얼로그 → avatar/상태/소개 변경 → 저장 → 즉시 반영
- [ ] 메시지 버블 avatar가 user.profileImageUrl 사용
- [ ] 멤버 시트 행 avatar 일관 표시
- [ ] 음소거된 사용자가 EDIT 시도 → 거부 (백엔드 단위 테스트)
- [ ] 운영자가 12분 mute → 정상 동작
- [ ] 모든 새/변경된 백엔드 단위 테스트 green
- [ ] frontend test green
- [ ] CI green

---

## End of Spec
