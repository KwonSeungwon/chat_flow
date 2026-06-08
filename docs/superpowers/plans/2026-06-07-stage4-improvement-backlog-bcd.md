# Stage 4 Improvement Backlog (B + C + D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Continue the Stage 4 refactoring track with three independent, evidence-backed workstreams: finish the MapStruct/wire-DTO migration (B), consolidate + commit the in-flight frontend `ApiResponse` refactor (C), and close two concrete backend gaps — cursor-pagination test coverage and the outbox poison-pill (D).

**Architecture:** Each Part below is independently shippable. Part B follows the exact pattern set by `ChatMessageResponseMapper` (Stage 4-B mapper #3): a wire DTO in `common/dto`, a MapStruct mapper in `chat-service/mapper`, controller migration, and a JSON leak-regression test. Part C commits an existing 90%-done refactor then extends one helper. Part D is pure TDD test-adds plus a small resilience guard.

**Tech Stack:** Spring Boot 3.2 / Java 17 / MapStruct / JUnit5 + AssertJ (backend); Flutter / Dart / Riverpod / Dio (frontend); Gradle multi-module.

**Out of scope (need their own brainstorm + plan — too large for bite-sized tasks here):**
- `chat_input.dart` (992 LOC) decomposition.
- `ChatRoomController` (338 LOC / 9 deps) and `ChatRoomService` (213 LOC / 9 deps) decomposition.
- Full migration of the remaining ~13 frontend files to the `apiResponse*` helpers (incremental long tail; helper is available for opportunistic adoption).
- Workstream A (deploy/infra: stale GHCR fleet, broken `deploy-k3s.yml` secrets, GHCR casing bug) — deferred by user; tracked separately.

**Decisions already made (do NOT revisit):**
- `BanDto`, `ReportDto`, `MentionItemDto` stay **hand-rolled** — they require joins/lookups/truncation; MapStruct `@Context` overhead is not worth it (verified during review).
- `Result<T, ChatErrorCode>` is NOT being mass-migrated — that is a documentation decision, not code in this plan.

---

## Part B — Stage 4-B MapStruct / wire-DTO completion

### Task B1: Close the `ChatRoom` entity-on-wire leak (mapper #4)

Five `ChatRoomController` endpoints return the raw JPA `ChatRoom` entity. This leaks the derived `full` key (from `isFull()`) and auto-exposes any future column. (`password` is already safe via `@JsonProperty(WRITE_ONLY)`.) Create a response DTO + mapper, mirroring `ChatMessageResponse`. The frontend `ChatRoom.fromJson` (`frontend/lib/shared/models/chat_room.dart:34-50`) is the **only** consumer (no other microservice reads `/api/chat/rooms` REST).

**Files:**
- Create: `common/src/main/java/com/chatflow/common/dto/ChatRoomResponse.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/mapper/ChatRoomMapper.java`
- Create test: `chat-service/src/test/java/com/chatflow/chat/mapper/ChatRoomMapperTest.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java` (methods `getAllRooms`, `getRoom`, `createRoom`, `getOrCreateRoom`, and the DM-create endpoint — keep `@RequestBody ChatRoom` on the request side; only map on the way **out**)

- [ ] **Step 1: Write the failing mapper test**

Mirror `ChatMessageResponseMapperTest`. The JSON test is the leak guard.

```java
package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.common.dto.ChatRoomResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomMapperTest {

    private final ChatRoomMapper mapper = Mappers.getMapper(ChatRoomMapper.class);

    private ChatRoom fullRoom() {
        return ChatRoom.builder()
                .id("room-1").name("General").description("desc").color("#FF0000")
                .externalId("ext-1").roomType(RoomType.GENERAL)
                .isPrivate(true).password("secret").allowInvites(false).allowedRoles("OWNER,MOD")
                .participantCount(3).maxParticipants(10).createdBy("user-1")
                .createdAt(LocalDateTime.of(2026, 6, 7, 9, 0))
                .lastMessageAt(LocalDateTime.of(2026, 6, 7, 10, 0))
                .pinnedMessageId("msg-9")
                .build();
    }

    @Test
    void toResponse_copies_all_wire_fields() {
        ChatRoomResponse dto = mapper.toResponse(fullRoom());
        assertThat(dto.getId()).isEqualTo("room-1");
        assertThat(dto.getName()).isEqualTo("General");
        assertThat(dto.getDescription()).isEqualTo("desc");
        assertThat(dto.getColor()).isEqualTo("#FF0000");
        assertThat(dto.getExternalId()).isEqualTo("ext-1");
        assertThat(dto.getRoomType()).isEqualTo(RoomType.GENERAL);
        assertThat(dto.isPrivate()).isTrue();
        assertThat(dto.isAllowInvites()).isFalse();
        assertThat(dto.getAllowedRoles()).isEqualTo("OWNER,MOD");
        assertThat(dto.getParticipantCount()).isEqualTo(3);
        assertThat(dto.getMaxParticipants()).isEqualTo(10);
        assertThat(dto.getCreatedBy()).isEqualTo("user-1");
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 9, 0));
        assertThat(dto.getLastMessageAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 10, 0));
        assertThat(dto.getPinnedMessageId()).isEqualTo("msg-9");
    }

    @Test
    void toResponse_returns_null_for_null_input() {
        assertThat(mapper.toResponse(null)).isNull();
    }

    @Test
    void toResponseList_maps_each_element() {
        List<ChatRoomResponse> dtos = mapper.toResponseList(List.of(
                ChatRoom.builder().id("a").name("A").participantCount(0).build(),
                ChatRoom.builder().id("b").name("B").participantCount(1).build()));
        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(1).getName()).isEqualTo("B");
    }

    @Test
    void serialized_json_omits_password_and_derived_full_keeps_wire_keys() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        ChatRoomResponse dto = mapper.toResponse(fullRoom());
        @SuppressWarnings("unchecked")
        Map<String, Object> json = om.convertValue(dto, Map.class);
        assertThat(json).doesNotContainKey("password");
        assertThat(json).doesNotContainKey("full");   // entity's isFull() leaked this
        assertThat(json).containsKeys(
                "id", "name", "description", "color", "externalId", "roomType",
                "isPrivate", "allowInvites", "allowedRoles", "participantCount",
                "maxParticipants", "createdBy", "createdAt", "lastMessageAt", "pinnedMessageId");
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run: `./gradlew :chat-service:compileTestJava`
Expected: FAIL — `cannot find symbol: ChatRoomResponse` / `ChatRoomMapper`.

- [ ] **Step 3: Create the DTO**

Note: `@JsonProperty("isPrivate")` forces the key to `isPrivate` to match the entity's explicit annotation (without it, Jackson would emit `private`). The frontend reads both, but match exactly.

```java
package com.chatflow.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wire DTO for chat-room REST responses. Mirrors {@code ChatRoom} entity
 * field-for-field EXCEPT: {@code password} (already write-only on the entity)
 * and the derived {@code full} key (entity's {@code isFull()} leaked it; the
 * frontend recomputes via its own {@code isFull} getter). Stage 4-B mapper #4,
 * following {@code ChatMessageResponse}.
 *
 * <p>{@code roomType} is the {@code RoomType} enum — Jackson serializes it to
 * its name string, byte-identical to the entity's {@code @Enumerated(STRING)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomResponse {
    private String id;
    private String name;
    private String description;
    private String color;
    private String externalId;
    private RoomType roomType;
    @JsonProperty("isPrivate")
    private boolean isPrivate;
    private boolean allowInvites;
    private String allowedRoles;
    private Integer participantCount;
    private Integer maxParticipants;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
    private String pinnedMessageId;
}
```

NOTE: `RoomType` currently lives in `com.chatflow.chat.entity`. `common` cannot depend on `chat-service`. **Before writing the DTO**, check where `RoomType` is referenced: if `common` cannot see it, change the DTO field to `private String roomType;` and map it via `@Mapping(target = "roomType", expression = "java(room.getRoomType() != null ? room.getRoomType().name() : null)")` in the mapper (and update the test to assert the string `"GENERAL"`). Prefer the `String` form to avoid a cross-module enum dependency.

- [ ] **Step 4: Create the mapper**

`isPrivate` hits the Lombok `is`-prefix MapStruct quirk (same as `isAiGenerated` in `ChatMessageResponseMapper`) → bridge with an expression. `allowInvites` (no `is`-prefix field) should auto-map; the test confirms.

```java
package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.common.dto.ChatRoomResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * MapStruct mapper {@link ChatRoom} entity → {@link ChatRoomResponse} wire DTO.
 * Drops the entity-only {@code password} (write-only) and the derived
 * {@code full} key. Stage 4-B mapper #4.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ChatRoomMapper {

    @Mapping(target = "isPrivate", expression = "java(room.isPrivate())")
    ChatRoomResponse toResponse(ChatRoom room);

    List<ChatRoomResponse> toResponseList(List<ChatRoom> rooms);
}
```

- [ ] **Step 5: Run the test — verify it passes**

Run: `./gradlew :chat-service:test --tests "com.chatflow.chat.mapper.ChatRoomMapperTest"`
Expected: PASS (4 tests). If a MapStruct `Unmapped target property: allowInvites` warning appears and the field-copy test fails, add `@Mapping(target = "allowInvites", expression = "java(room.isAllowInvites())")` and re-run.

- [ ] **Step 6: Migrate the controller — inject the mapper**

In `ChatRoomController.java`, add the field after `chatMessageResponseMapper`:
```java
    private final ChatRoomMapper chatRoomMapper;
```
Add imports:
```java
import com.chatflow.chat.mapper.ChatRoomMapper;
import com.chatflow.common.dto.ChatRoomResponse;
```

- [ ] **Step 7: Migrate the five response sites**

`getAllRooms` — change return type to `ResponseEntity<ApiResponse<List<ChatRoomResponse>>>` and map both return paths:
```java
        return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponseList(rooms)));
        // ...and the filtered branch:
        return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponseList(visible)));
```
`getRoom` — `.map(room -> ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponse(room))))`.
`createRoom` — return type `ResponseEntity<ApiResponse<ChatRoomResponse>>`; `ApiResponse.ok(chatRoomMapper.toResponse(saved), "채팅방이 생성되었습니다.")`. Keep `@Valid @RequestBody ChatRoom request` unchanged.
`getOrCreateRoom` — `ApiResponse.ok(chatRoomMapper.toResponse(room))`.
DM-create endpoint (the method returning `ChatRoom` from `dmRoomService`) — wrap its returned room with `chatRoomMapper.toResponse(...)`.

- [ ] **Step 8: Run the full chat-service suite + verify boundary**

Run: `./gradlew :chat-service:test`
Expected: BUILD SUCCESSFUL, test count = current + 4.
Then manually confirm against `frontend/lib/shared/models/chat_room.dart:34-50` that every key it reads (`id, name, description, color, roomType, allowedRoles, isPrivate, participantCount, maxParticipants, createdBy, createdAt, lastMessageAt, pinnedMessageId`) is still emitted. `externalId` is only an `id` fallback (id is always non-null) — preserved anyway.

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/com/chatflow/common/dto/ChatRoomResponse.java \
        chat-service/src/main/java/com/chatflow/chat/mapper/ChatRoomMapper.java \
        chat-service/src/test/java/com/chatflow/chat/mapper/ChatRoomMapperTest.java \
        chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java
git commit -m "feat(chat-service): Stage 4-B mapper #4 — ChatRoomResponse DTO + mapper

Closes the ChatRoom entity-on-wire leak across 5 ChatRoomController endpoints.
Drops the derived 'full' key (isFull()); password already write-only. Wire
shape preserved for every field the Flutter ChatRoom.fromJson reads.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task B2: `ScheduledMessageMapper` (mapper #5)

Replace the hand-rolled `ScheduledMessageDto.from()` with MapStruct. The only non-trivial field is `status` (enum → String).

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/mapper/ScheduledMessageMapper.java`
- Create test: `chat-service/src/test/java/com/chatflow/chat/mapper/ScheduledMessageMapperTest.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/ScheduledMessageController.java:48,67` (call sites) and the service that builds the list, if any (`grep -rn "ScheduledMessageDto.from" chat-service/src/main`).

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledMessageMapperTest {

    private final ScheduledMessageMapper mapper = Mappers.getMapper(ScheduledMessageMapper.class);

    @Test
    void toDto_copies_fields_and_stringifies_status() {
        ScheduledMessageEntity e = ScheduledMessageEntity.builder()
                .id(7L).chatRoomId("room-1").userId("u").username("alice").content("hi")
                .scheduledAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .status(ScheduledMessageStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 6, 7, 11, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 7, 11, 0))
                .version(0L)
                .build();

        ScheduledMessageDto dto = mapper.toDto(e);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.chatRoomId()).isEqualTo("room-1");
        assertThat(dto.content()).isEqualTo("hi");
        assertThat(dto.scheduledAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 12, 0));
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(dto.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 11, 0));
    }

    @Test
    void toDto_returns_null_for_null_input() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
```

- [ ] **Step 2: Run — verify it fails to compile** (`./gradlew :chat-service:compileTestJava` → `cannot find symbol: ScheduledMessageMapper`).

- [ ] **Step 3: Create the mapper**

`ScheduledMessageDto` is a record (constructor mapping). Map `status` via expression.

```java
package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.entity.ScheduledMessageEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** Stage 4-B mapper #5: ScheduledMessageEntity → ScheduledMessageDto. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ScheduledMessageMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus() != null ? entity.getStatus().name() : null)")
    ScheduledMessageDto toDto(ScheduledMessageEntity entity);
}
```

- [ ] **Step 4: Run — verify PASS** (`./gradlew :chat-service:test --tests "com.chatflow.chat.mapper.ScheduledMessageMapperTest"`).

- [ ] **Step 5: Migrate call sites + delete the hand-roll**

Inject `ScheduledMessageMapper` into `ScheduledMessageController` (and any service using `ScheduledMessageDto.from`). Replace `ScheduledMessageDto.from(x)` with `scheduledMessageMapper.toDto(x)`. Delete the static `from(...)` method from `ScheduledMessageDto.java` once no callers remain (`grep -rn "ScheduledMessageDto.from" chat-service/src` must return nothing).

- [ ] **Step 6: Run full suite + commit**

Run: `./gradlew :chat-service:test` → BUILD SUCCESSFUL.
```bash
git add chat-service/src/main/java/com/chatflow/chat/mapper/ScheduledMessageMapper.java \
        chat-service/src/test/java/com/chatflow/chat/mapper/ScheduledMessageMapperTest.java \
        chat-service/src/main/java/com/chatflow/chat/dto/ScheduledMessageDto.java \
        chat-service/src/main/java/com/chatflow/chat/controller/ScheduledMessageController.java
git commit -m "refactor(chat-service): Stage 4-B mapper #5 — ScheduledMessageMapper

Replaces hand-rolled ScheduledMessageDto.from() with MapStruct (status enum
stringified via expression). Wire shape unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task B3: `MemberMapper` (mapper #6)

`MemberDto.from(RoomMemberEntity)` is a pure 4-field copy (`userId, username, role, mutedUntil`) — trivial MapStruct, zero `@Mapping`.

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/mapper/MemberMapper.java`
- Create test: `chat-service/src/test/java/com/chatflow/chat/mapper/MemberMapperTest.java`
- Modify: call sites (`grep -rn "MemberDto.from" chat-service/src/main`) + delete `MemberDto.from`.

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.MemberDto;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemberMapperTest {

    private final MemberMapper mapper = Mappers.getMapper(MemberMapper.class);

    @Test
    void toDto_copies_all_fields() {
        RoomMemberEntity e = RoomMemberEntity.builder()
                .userId("u").username("alice").role(RoomRole.MOD)
                .mutedUntil(LocalDateTime.of(2026, 6, 7, 12, 0))
                .build();
        MemberDto dto = mapper.toDto(e);
        assertThat(dto.userId()).isEqualTo("u");
        assertThat(dto.username()).isEqualTo("alice");
        assertThat(dto.role()).isEqualTo(RoomRole.MOD);
        assertThat(dto.mutedUntil()).isEqualTo(LocalDateTime.of(2026, 6, 7, 12, 0));
    }

    @Test
    void toDto_returns_null_for_null_input() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toDtoList_maps_each() {
        List<MemberDto> dtos = mapper.toDtoList(List.of(
                RoomMemberEntity.builder().userId("a").username("A").role(RoomRole.OWNER).build(),
                RoomMemberEntity.builder().userId("b").username("B").role(RoomRole.MEMBER).build()));
        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).role()).isEqualTo(RoomRole.OWNER);
    }
}
```

NOTE: before Step 1, confirm `RoomMemberEntity` has a Lombok `@Builder` (if not, use its actual constructor/setters in the test). Run `grep -n "@Builder\|class RoomMemberEntity" chat-service/src/main/java/com/chatflow/chat/entity/RoomMemberEntity.java`.

- [ ] **Step 2: Run — verify it fails to compile.**

- [ ] **Step 3: Create the mapper**

```java
package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.MemberDto;
import com.chatflow.chat.entity.RoomMemberEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/** Stage 4-B mapper #6: RoomMemberEntity → MemberDto (pure copy). */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MemberMapper {
    MemberDto toDto(RoomMemberEntity entity);
    List<MemberDto> toDtoList(List<RoomMemberEntity> entities);
}
```

- [ ] **Step 4: Run — verify PASS.**

- [ ] **Step 5: Migrate call sites, delete `MemberDto.from`, run full suite, commit** (same shape as B2 Step 5-6; commit message `refactor(chat-service): Stage 4-B mapper #6 — MemberMapper`).

---

## Part C — Frontend `ApiResponse` consolidation

### Task C1: Verify + commit the in-flight `ApiResponse` foundation

There is uncommitted work in the tree: a new `frontend/lib/core/network/api_response.dart` (4 helpers: `unwrapApiResponse`, `apiResponseMap`, `apiResponseList`, `apiResponseField`) plus 6 files migrated to use them (`room_admin_api.dart`, `chat_notifier.dart`, `mentions_provider.dart`, `quick_reply_provider.dart`, `scheduled_messages_provider.dart`, `profile_api.dart`). Review verdict: coherent foundation, safe to commit. (`AGENTS.md` untracked is unrelated — leave it.) **This commit is a foundation; ~13 other files still hand-parse `data['data']` — that long tail is explicitly NOT this task.**

**Files:** the 7 already-changed files (1 new + 6 modified). No new edits unless `flutter analyze` flags something.

- [ ] **Step 1: Confirm the working-tree state**

Run: `cd frontend && git status --short`
Expected: `?? lib/core/network/api_response.dart` + the 6 ` M` files (+ unrelated `?? ../AGENTS.md`).

- [ ] **Step 2: Static analysis gate**

Run: `cd frontend && flutter analyze lib/core/network/api_response.dart lib/features/chat/admin/room_admin_api.dart lib/features/chat/chat_notifier.dart lib/features/chat/mentions_provider.dart lib/features/chat/quick_reply_provider.dart lib/features/chat/scheduled_messages_provider.dart lib/features/profile/profile_api.dart`
Expected: "No issues found!" If issues appear, fix them minimally (likely unused imports from removed local `_unwrap` helpers) before committing.

- [ ] **Step 3: Run the existing unwrap test**

Run: `cd frontend && flutter test test/features/chat/chat_notifier_summaries_unwrap_test.dart`
Expected: PASS (this test exercises `apiResponseList` via `parseSummariesResponse`). If the path differs, find it: `find frontend/test -name "*unwrap*"`.

- [ ] **Step 4: Commit the foundation**

```bash
git add frontend/lib/core/network/api_response.dart \
        frontend/lib/features/chat/admin/room_admin_api.dart \
        frontend/lib/features/chat/chat_notifier.dart \
        frontend/lib/features/chat/mentions_provider.dart \
        frontend/lib/features/chat/quick_reply_provider.dart \
        frontend/lib/features/chat/scheduled_messages_provider.dart \
        frontend/lib/features/profile/profile_api.dart
git commit -m "refactor(frontend): centralize API envelope unwrapping in api_response.dart

Adds unwrapApiResponse/apiResponseMap/apiResponseList/apiResponseField helpers
and migrates 6 api/provider files off inline data['data'] parsing. Foundation
only — remaining files migrate opportunistically.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task C2: Extend `apiResponseList` for the Page (`content`) shape + migrate `chat_rooms_provider`

`chat_rooms_provider.dart:47-54` handles three shapes: bare `List`, `{data: [...]}`, and `{content: [...]}` (Spring `Page`). The current `apiResponseList` only covers the first two — migrating blindly would drop the `Page` path. Extend the helper, then migrate.

**Files:**
- Modify: `frontend/lib/core/network/api_response.dart`
- Create test: `frontend/test/core/network/api_response_test.dart`
- Modify: `frontend/lib/features/chat/chat_rooms_provider.dart:47-54,96-100,139-141`

- [ ] **Step 1: Write the failing helper test**

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/api_response.dart';

void main() {
  group('apiResponseList', () {
    test('returns a bare list as-is', () {
      expect(apiResponseList([1, 2, 3]), [1, 2, 3]);
    });
    test('unwraps {data: [...]}', () {
      expect(apiResponseList({'data': [1, 2]}), [1, 2]);
    });
    test('unwraps Spring Page {content: [...]}', () {
      expect(apiResponseList({'content': [9]}), [9]);
    });
    test('unwraps {data: {content: [...]}}', () {
      expect(apiResponseList({'data': {'content': [5]}}), [5]);
    });
    test('returns empty list for null/malformed', () {
      expect(apiResponseList(null), const []);
      expect(apiResponseList({'foo': 'bar'}), const []);
    });
  });
}
```

NOTE: confirm the pubspec package name for the import. Run `grep "^name:" frontend/pubspec.yaml` — if it is not `chatflow`, adjust the `package:` import.

- [ ] **Step 2: Run — verify the `content` tests fail**

Run: `cd frontend && flutter test test/core/network/api_response_test.dart`
Expected: the two `content` tests FAIL (current helper returns `[]` for `{content: ...}`).

- [ ] **Step 3: Extend the helper**

```dart
List<dynamic> apiResponseList(Object? payload) {
  final data = unwrapApiResponse(payload);
  if (data is List) return data;
  // Spring Page shape: {content: [...]} (possibly nested under data:)
  if (data is Map && data['content'] is List) return data['content'] as List;
  return const [];
}
```

- [ ] **Step 4: Run — verify PASS** (all 5 tests).

- [ ] **Step 5: Migrate `chat_rooms_provider.dart`**

Replace the manual block at lines ~47-54:
```dart
      final list = apiResponseList(resp.data);
```
At lines ~96-100 (`fetchUnreadCounts`, expects a Map):
```dart
      final raw = apiResponseMap(resp.data);
      if (raw != null) { /* existing body using raw */ }
```
At lines ~139-141 (extract roomId — keep the `data['id']` fallback):
```dart
      final m = apiResponseMap(resp.data);
      final roomId = (m?['id'] ?? (resp.data is Map ? (resp.data as Map)['id'] : null))?.toString();
```
Add `import '../../core/network/api_response.dart';` at the top. Verify the imports resolve relative to the file's location (adjust `../` depth if needed).

- [ ] **Step 6: Analyze + commit**

Run: `cd frontend && flutter analyze lib/features/chat/chat_rooms_provider.dart lib/core/network/api_response.dart && flutter test test/core/network/api_response_test.dart`
Expected: no issues, tests PASS.
```bash
git add frontend/lib/core/network/api_response.dart \
        frontend/test/core/network/api_response_test.dart \
        frontend/lib/features/chat/chat_rooms_provider.dart
git commit -m "refactor(frontend): apiResponseList handles Spring Page shape; migrate chat_rooms_provider

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Part D — Backend gaps

### Task D1: Cover `MessageReadService` cursor pagination with tests

`MessageReadService` (which feeds the two endpoints migrated in Stage 4-B mapper #3) has **no test**. Add tests for `getMessagesByCursor` (the cursor logic) and the decryption passthrough, mocking the repository.

**Files:**
- Create test: `chat-service/src/test/java/com/chatflow/chat/service/read/MessageReadServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.service.read;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageReadServiceTest {

    private final ChatMessageRepository repo = mock(ChatMessageRepository.class);
    private final MessageEncryptor encryptor = mock(MessageEncryptor.class);
    private final MessageReadService service = new MessageReadService(repo, encryptor);

    private ChatMessageEntity msg(String id, String content) {
        return ChatMessageEntity.builder().messageId(id).chatRoomId("r").username("a")
                .content(content).timestamp(LocalDateTime.of(2026, 6, 7, 9, 0)).type("CHAT").build();
    }

    @Test
    void getMessagesByCursor_null_before_fetches_latest() {
        when(encryptor.isEnabled()).thenReturn(false);
        when(repo.findLatestByChatRoomId(eq("r"), any(Pageable.class)))
                .thenReturn(List.of(msg("m1", "hi")));

        List<ChatMessageEntity> out = service.getMessagesByCursor("r", null, 50);

        assertThat(out).hasSize(1);
        verify(repo).findLatestByChatRoomId(eq("r"), any(Pageable.class));
        verify(repo, never()).findByChatRoomIdBeforeCursor(any(), any(), any());
    }

    @Test
    void getMessagesByCursor_with_before_uses_cursor_query() {
        when(encryptor.isEnabled()).thenReturn(false);
        LocalDateTime before = LocalDateTime.of(2026, 6, 7, 8, 0);
        when(repo.findByChatRoomIdBeforeCursor(eq("r"), eq(before), any(Pageable.class)))
                .thenReturn(List.of(msg("m0", "older")));

        List<ChatMessageEntity> out = service.getMessagesByCursor("r", before, 50);

        assertThat(out.get(0).getMessageId()).isEqualTo("m0");
        verify(repo).findByChatRoomIdBeforeCursor(eq("r"), eq(before), any(Pageable.class));
    }

    @Test
    void getMessagesByCursor_decrypts_content_when_enabled() {
        when(encryptor.isEnabled()).thenReturn(true);
        when(encryptor.decrypt("cipher")).thenReturn("plain");
        when(repo.findLatestByChatRoomId(eq("r"), any(Pageable.class)))
                .thenReturn(List.of(msg("m1", "cipher")));

        List<ChatMessageEntity> out = service.getMessagesByCursor("r", null, 50);

        assertThat(out.get(0).getContent()).isEqualTo("plain");
    }
}
```

- [ ] **Step 2: Run — verify it fails for the right reason**

Run: `./gradlew :chat-service:test --tests "com.chatflow.chat.service.read.MessageReadServiceTest"`
Expected: compiles and runs. If repository method names (`findLatestByChatRoomId`, `findByChatRoomIdBeforeCursor`) differ, the compile error tells you — fix the test to match `ChatMessageRepository`'s actual signatures (these names are from `MessageReadService.java:39-40`). Tests should PASS immediately if the service already works — that is acceptable here because the goal is **characterization coverage of existing untested behavior** (not new behavior). If any test fails, you found a latent bug — investigate before proceeding.

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/test/java/com/chatflow/chat/service/read/MessageReadServiceTest.java
git commit -m "test(chat-service): cover MessageReadService cursor pagination + decryption

Characterization tests for getMessagesByCursor (null-before → latest, before →
cursor query) and decrypt passthrough. Closes a coverage gap on the service
feeding the message-history REST endpoints.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task D2: Outbox poison-pill cap (stop infinite retry of unsendable events)

`OutboxPoller` re-polls PENDING events every 200ms. An event that can never succeed (e.g. a payload that always fails `objectMapper.readValue`) is retried forever with an ERROR log each cycle. Add a retry counter and a terminal `FAILED` state so poison events stop after N attempts.

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/entity/OutboxEvent.java` (add `retryCount`, ensure `FAILED` status exists)
- Create migration: `chat-service/src/main/resources/db/migration/V<next>__outbox_retry_count.sql`
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/OutboxEventRepository.java` (a bulk "increment retry / mark FAILED" method)
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/outbox/OutboxPoller.java`
- Modify/extend test: `chat-service/src/test/java/com/chatflow/chat/service/outbox/OutboxPollerTest.java` (find exact name via `find chat-service/src/test -name "OutboxPoller*"`)

- [ ] **Step 1: Determine the next Flyway version**

Run: `ls chat-service/src/main/resources/db/migration/`
Use the next `V<n>` number. Confirm `OutboxEvent.OutboxStatus` enum members (`grep -n "OutboxStatus" chat-service/src/main/java/com/chatflow/chat/entity/OutboxEvent.java`); if `FAILED` is missing, add it.

- [ ] **Step 2: Write the failing poller test**

Add to the existing OutboxPoller test (adapt mocks to its style):
```java
@Test
void event_exceeding_max_retries_is_marked_failed_not_retried_forever() {
    // GIVEN an event whose payload always fails to parse / send, with retryCount already at MAX-1
    // WHEN doPoll() runs and the send fails
    // THEN the repository is asked to mark it FAILED (not left PENDING)
    // (Use the same mock-based harness the existing OutboxPollerTest uses:
    //  stub findTop50ByStatusOrderByCreatedAtAsc to return the poison event,
    //  stub kafkaTemplate.send(...) to return a failed CompletableFuture,
    //  verify the new markFailed/incrementRetry repository call.)
}
```
Fill in the body matching the existing test's mocking approach (KafkaTemplate returns a `CompletableFuture.failedFuture(...)`). The assertion: when `retryCount >= MAX_RETRIES`, `OutboxPoller` calls the new repository method to set status `FAILED`.

- [ ] **Step 3: Run — verify it fails** (new repository method does not exist yet).

- [ ] **Step 4: Implement**

- `OutboxEvent`: add `@Column(name="retry_count") @Builder.Default private int retryCount = 0;` and `FAILED` to the status enum if absent.
- Migration SQL:
```sql
ALTER TABLE outbox_events ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
```
- `OutboxEventRepository`: add
```java
@Modifying
@Query("UPDATE OutboxEvent o SET o.status = 'FAILED', o.processedAt = :now WHERE o.id IN :ids")
int markFailed(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

@Modifying
@Query("UPDATE OutboxEvent o SET o.retryCount = o.retryCount + 1 WHERE o.id IN :ids")
int incrementRetry(@Param("ids") List<Long> ids);
```
(Match the existing `markProcessed` annotation style; verify the `processedAt` field name exists.)
- `OutboxPoller.doPoll()`: after collecting `succeeded`, compute the failed set (`pendingEvents − succeeded` that were attempted). Within the transaction, partition by `retryCount + 1 >= MAX_RETRIES` (`private static final int MAX_RETRIES = 10;`): call `markFailed(poisonIds, now)` for those at the cap, `incrementRetry(retryIds)` for the rest. Log a single WARN listing FAILED ids.

- [ ] **Step 5: Run the poller test + full suite**

Run: `./gradlew :chat-service:test --tests "*OutboxPoller*"` then `./gradlew :chat-service:test`
Expected: PASS. (Flyway is disabled in the test profile per `application-test.yml`; `ddl-auto=create-drop` picks up the new column automatically.)

- [ ] **Step 6: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/entity/OutboxEvent.java \
        chat-service/src/main/java/com/chatflow/chat/repository/OutboxEventRepository.java \
        chat-service/src/main/java/com/chatflow/chat/service/outbox/OutboxPoller.java \
        chat-service/src/main/resources/db/migration/ \
        chat-service/src/test/java/com/chatflow/chat/service/outbox/
git commit -m "fix(chat-service): cap outbox retries — mark poison events FAILED after 10 attempts

Prevents unsendable outbox events from re-polling every 200ms forever (log spam
+ wasted Kafka calls). Adds retry_count column + terminal FAILED status.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Suggested execution order

1. **C1** (commit the in-flight foundation — clears the dirty working tree first, no dependencies).
2. **B1 → B2 → B3** (mapper track, momentum from mapper #3; B1 highest value).
3. **D1** (fast, high-value test coverage on recently-touched code).
4. **C2** (helper extension + chat_rooms_provider).
5. **D2** (touches schema — do last, most care).

---

## Self-Review

**Spec coverage** (against the chosen workstreams B/C/D):
- B (Stage 4-B completion): B1 ChatRoom leak ✓, B2 ScheduledMessage mapper ✓, B3 Member mapper ✓; Ban/Report/Mention explicitly kept hand-rolled ✓.
- C (frontend): C1 commit foundation ✓, C2 extend helper + migrate the trickiest file ✓; DioErrorHandler + chat_input split correctly deferred to own plans ✓.
- D (backend): D1 MessageReadService tests ✓, D2 OutboxPoller poison-pill ✓; ChatRoomController/Service decomposition correctly deferred to own plan ✓.

**Placeholder scan:** D2 Step 2's test body is intentionally a guided stub because the assertion must match the existing `OutboxPollerTest` mocking harness (not yet read) — flagged inline with exactly what to stub/verify, not a bare "TODO". All other code steps contain complete code. Several steps include a "confirm X before writing" guard (RoomType module visibility, RoomMemberEntity @Builder, repository method names, pubspec package name, Flyway version) — these are verification steps, not placeholders.

**Type consistency:** mapper method names are consistent within each task (`toResponse`/`toResponseList` for entity→response DTOs matching `ChatMessageResponseMapper`; `toDto`/`toDtoList` for the existing `*Dto` records matching `MessageEditHistoryMapper`). DTO field names mirror their source entities exactly (the basis of the byte-identical wire claim). Frontend helper names (`apiResponseList`/`apiResponseMap`) match `api_response.dart`.

**Known cross-checks the executor MUST honor:** B1's `RoomType` cross-module visibility (use `String` if `common` can't see the enum), and C2's import path depth — both called out inline.
