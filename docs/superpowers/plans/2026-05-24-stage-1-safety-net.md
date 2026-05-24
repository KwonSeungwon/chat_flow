# Stage 1 — Safety Net Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Mockito-style unit tests for 25 untested backend units (19 services + 6 controllers/guards) so Stages 2 and 3 can refactor with a regression floor in place.

**Architecture:** Pure unit tests — `@ExtendWith(MockitoExtension.class)` per existing convention, manual constructor wiring in `@BeforeEach`, `@Nested` for case grouping. Controller tests use `MockMvc` with the `GlobalExceptionHandler` already wired in `FcmControllerTest`. No `@SpringBootTest`, no slice tests, no Testcontainers.

**Tech Stack:** JUnit 5, Mockito (`MockitoExtension`, `@Mock`, `ArgumentCaptor`), Spring `MockMvc`, `SimpleMeterRegistry` for Micrometer counters, in-repo `KafkaTopics` / `ChatMessage` DTOs.

**Source:** spec `docs/superpowers/specs/2026-05-24-refactor-three-stage-design.md`

**Branches:**
- PR-1: `test/stage-1-services` — 19 service tests
- PR-2: `test/stage-1-controllers` — 6 controller/guard tests

**Exit criteria:** `./gradlew :chat-service:test` reports `tests=350+ failures=0 errors=0`.

---

## Reference test pattern (read once before any task)

The repo's house style is set by `MessageSenderServiceMuteGateTest`. Mirror it: imports, layout, helpers, naming.

```java
package com.chatflow.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FooServiceTest {

    @Mock private DepA depA;
    @Mock private DepB depB;

    private FooService fooService;

    @BeforeEach
    void setUp() {
        fooService = new FooService(depA, depB);
    }

    @Nested
    class HappyPath {
        @Test
        void doesTheThing() {
            when(depA.lookup("k")).thenReturn(Optional.of("v"));
            String result = fooService.act("k");
            assertEquals("V", result);
            verify(depB).publish(eq("V"));
        }
    }

    @Nested
    class ErrorCases {
        @Test
        void returnsFalseWhenLookupMisses() {
            when(depA.lookup(any())).thenReturn(Optional.empty());
            assertFalse(fooService.act("missing"));
            verifyNoInteractions(depB);
        }
    }
}
```

**Rules baked into the convention:**
- `@Mock` dependencies, manual `new FooService(...)` in `setUp` — no field injection, no Spring context.
- One `@Nested` class per scenario family (`HappyPath`, `ErrorCases`, `MutedUser`, etc.).
- Verify side effects with `verify(...)` / `ArgumentCaptor`, not by reading mock fields.
- `verifyNoInteractions` for "did not happen" assertions — clearer than `verify(..., never())` when the mock should be untouched.
- For services that publish counters: inject `MeterRegistry registry = new SimpleMeterRegistry();` in `setUp` and assert on `registry.counter("name").count()`.
- Helper factory methods (`createMessage(type)`, `member(mutedUntil)`) for repeated value-object construction. Put them under the `setUp`.

For controller tests (`FcmControllerTest` is the model):
- `MockMvc.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())` so 4xx mappings come through.
- Assertion shape: `andExpect(status().is4xxClientError())` for shape checks, `andExpect(jsonPath("$.success").value(false))` for payload.
- Send `X-User-Id` / `X-Username` via `header(...)`.

---

# PR-1 — Service tests (19 units)

**Branch:** `test/stage-1-services` (off `develop`).
**Reviewer agent:** `superpowers:code-reviewer` MUST run before merging into `develop`.

## Task 1: Branch off develop

**Files:** none (git only).

- [ ] **Step 1: Create branch**

```bash
git checkout develop && git pull --ff-only origin develop
git checkout -b test/stage-1-services
```

- [ ] **Step 2: Verify clean tree**

```bash
git status
```
Expected: `nothing to commit, working tree clean`.

---

## Task 2: ChatRoomServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/ChatRoomServiceTest.java`

**Dependencies to mock:** `ChatRoomRepository`, `ChatMessageRepository`, `StringRedisTemplate`, `ObjectMapper` (real — `new ObjectMapper()` is fine, it's stateless), `RedisHealthTracker`, `PasswordEncoder`, `SimpMessagingTemplate`, `RoomCacheEvictor`, `RoomMembershipService`.

**Scenarios:**

- [ ] **Step 1: Write the failing test file**

Test cases to include in `@Nested` groups:

`@Nested GetAllRooms`:
- `returns_cached_list_when_redis_hit`: stub `redisTemplate.opsForValue().get(ROOMS_LIST_KEY)` to JSON; verify `chatRoomRepository.findAllOrderByLastActivity()` is NOT called.
- `falls_back_to_repository_on_cache_miss_and_caches_result`: stub `redisTemplate.opsForValue().get(...)` to `null`; verify `chatRoomRepository.findAllOrderByLastActivity()` is called once; verify `opsForValue().set(ROOMS_LIST_KEY, any(), eq(Duration.ofSeconds(30)))` via `ArgumentCaptor`.
- `skips_cache_when_circuit_open`: stub `redisHealth.isCircuitOpen()` to `true`; verify repo is hit; verify `opsForValue().get` never called.

`@Nested GetRoom`:
- `returns_cached_when_redis_hit`
- `falls_back_to_repo_and_caches_with_5min_ttl`

`@Nested CreateRoom`:
- `seeds_creator_as_owner_via_membership_service_and_evicts_cache`: capture `roomMembershipService.addMemberIfAbsent(...)` args; assert role == `OWNER`, username == creatorUsername; verify `roomCacheEvictor.evict(savedId)`.
- `encrypts_password_when_present`: input room with `password = "pw"`; capture `chatRoomRepository.save(room)`; assert `room.getPassword().startsWith("$2")` after `passwordEncoder.encode("pw")` is stubbed to return `"$2a$10$..."`.

`@Nested VerifyRoomPassword`:
- `accepts_bcrypt_hash_when_matches`
- `rehashes_legacy_plaintext_on_match`: stored value is non-bcrypt; assert `chatRoomRepository.save(...)` is called with the re-hashed value; assert `roomCacheEvictor.evict(...)` is called.
- `returns_false_on_mismatch`

`@Nested DeleteRoom`:
- `broadcasts_room_deleted_then_clears_data_and_cache`: verify `messagingTemplate.convertAndSend(eq("/topic/chat/" + ROOM_ID), any())` is called *before* `chatMessageRepository.deleteAllByChatRoomId` (`InOrder`).

`@Nested UpdateRoomSettings`:
- `updates_only_provided_fields_and_evicts_cache`: pass `name="x", description=null`; assert description unchanged.

- [ ] **Step 2: Run test**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.ChatRoomServiceTest --no-daemon
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/test/java/com/chatflow/chat/service/ChatRoomServiceTest.java
git commit -m "test(chat-service): cover ChatRoomService (cache, password, delete, settings)"
```

---

## Task 3: RoomMembershipServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/RoomMembershipServiceTest.java`

**Mocks:** `RoomMemberRepository`, `StringRedisTemplate`, `RedisHealthTracker`, `SimpMessagingTemplate`, `ParticipantService`, `RoomCacheEvictor`.

**Scenarios:**

- [ ] **Step 1: Write tests**

`@Nested AddMemberIfAbsent`:
- `inserts_member_with_default_role_MEMBER`: assert captured `RoomMemberEntity.role == MEMBER`.
- `inserts_with_OWNER_when_4_arg_overload_called`
- `noop_when_already_exists`: stub `existsByRoomIdAndUserId(...)` true; verify `save` never called.
- `noop_on_blank_userId`: pass `""` and `null`; verify zero interactions with repo.
- `tolerates_concurrent_insert_race`: stub `save(...)` to throw `DataIntegrityViolationException`; assert no exception bubbles up.

`@Nested LeaveRoom`:
- `removes_userId_prefixed_entries_from_redis_set_and_syncs_count`: stub `members(...)` to return set including `"user-1:sess-a:Alice"` and `"user-2:sess-b:Bob"`; verify only `user-1:...` entries are removed.
- `broadcasts_LEAVE_system_message`: capture `messagingTemplate.convertAndSend` payload; assert `type == "LEAVE"`, `content` ends with `"채팅방을 나갔습니다."`.
- `skips_redis_cleanup_when_circuit_open`: stub `isCircuitOpen() == true`; verify `opsForSet().members` never called; broadcast and count sync still happen.

`@Nested SendInviteMessage`:
- `publishes_SYSTEM_payload_with_inviter_and_target_in_content`

- [ ] **Step 2: Run**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.RoomMembershipServiceTest --no-daemon
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/test/java/com/chatflow/chat/service/RoomMembershipServiceTest.java
git commit -m "test(chat-service): cover RoomMembershipService (seed, leave, invite-message)"
```

---

## Task 4: MessagePinServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MessagePinServiceTest.java`

**Mocks:** `ChatMessageRepository`, `ChatRoomRepository`, `SimpMessagingTemplate`, `RoomCacheEvictor`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `pins_when_message_belongs_to_same_room_and_is_not_deleted`: assert `chat_rooms.pinned_message_id` set via `chatRoomRepository.save(room)`; assert broadcast `type=MESSAGE_PINNED`.
- `rejects_pin_when_message_belongs_to_different_room`: returns false; verify zero `save` calls.
- `rejects_pin_when_message_is_deleted`
- `rejects_pin_when_message_not_found`
- `unpin_clears_pinned_message_id_and_broadcasts`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.MessagePinServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/MessagePinServiceTest.java
git commit -m "test(chat-service): cover MessagePinService validation + broadcast"
```

---

## Task 5: MessageReactionServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MessageReactionServiceTest.java`

**Mocks:** `ChatMessageRepository`, `SimpMessagingTemplate`, `ObjectMapper`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `adds_user_to_emoji_list_when_first_reactor`: starts from empty reactions; resulting JSON has `{"👍": ["u1"]}`.
- `appends_user_when_others_already_reacted_with_same_emoji`
- `removes_user_when_already_in_list_toggling_off`
- `removes_emoji_key_when_last_user_unreacts`
- `broadcasts_REACTION_UPDATED_with_full_map`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.MessageReactionServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/MessageReactionServiceTest.java
git commit -m "test(chat-service): cover MessageReactionService toggle + broadcast"
```

---

## Task 6: UnreadCountServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/UnreadCountServiceTest.java`

**Mocks:** `StringRedisTemplate`, `ChatMessageRepository`, `RedisHealthTracker`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `returns_zero_when_lastReadMessageId_blank`
- `counts_messages_after_lastRead_via_repo_when_cache_miss`
- `returns_redis_cached_value_when_present`
- `batched_lookup_for_multiple_rooms_returns_map`: input 3 rooms, one with no read marker; result map has 3 entries.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.UnreadCountServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/UnreadCountServiceTest.java
git commit -m "test(chat-service): cover UnreadCountService cache + batch lookup"
```

---

## Task 7: ReadReceiptServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/ReadReceiptServiceTest.java`

**Mocks:** `StringRedisTemplate`, `SimpMessagingTemplate`, `RedisHealthTracker`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `markRead_writes_lastReadMessageId_and_readAt_keys`: verify both `chatflow:read:roomId:userId` and `chatflow:readat:roomId:userId` are set.
- `markRead_broadcasts_READ_RECEIPT_on_topic`
- `updateReadAt_writes_only_readAt_key`
- `getRoomReadPositions_returns_userId_to_messageId_map_excluding_caller`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.ReadReceiptServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/ReadReceiptServiceTest.java
git commit -m "test(chat-service): cover ReadReceiptService Redis writes + broadcast"
```

---

## Task 8: MessageEditHistoryRetentionServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MessageEditHistoryRetentionServiceTest.java`

**Mocks:** `MessageEditHistoryRepository`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `purges_with_cutoff_minus_retentionDays_and_batches_until_drained`: stub `deleteBatchOlderThan(...)` to return `5000, 5000, 2300` then `0`; assert called 3 times; capture cutoff arg and verify it's roughly `now - 90d`.
- `single_batch_when_first_call_returns_lt_batchSize`: stub to return `1234`; verify exactly one call.
- `respects_property_override`: use `@TestPropertySource`-style assignment via reflection on `retentionDays` field (Field.setAccessible) — set to `30` and verify cutoff drift.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.MessageEditHistoryRetentionServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/MessageEditHistoryRetentionServiceTest.java
git commit -m "test(chat-service): cover edit-history retention cutoff + batch loop"
```

---

## Task 9: MessageRetentionServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MessageRetentionServiceTest.java`

**Mocks:** `ChatMessageRepository`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `purges_messages_older_than_retentionDays_in_batches`: same shape as Task 8 but against `chatMessageRepository.deleteBatchOlderThan`.
- `single_batch_when_first_call_under_threshold`
- `cutoff_is_now_minus_retentionDays`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.MessageRetentionServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/MessageRetentionServiceTest.java
git commit -m "test(chat-service): cover MessageRetentionService cutoff + batch loop"
```

---

## Task 10: InviteLinkServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/InviteLinkServiceTest.java`

**Mocks:** `StringRedisTemplate`, `RedisHealthTracker`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `createInviteToken_writes_roomId_under_token_key_with_24h_ttl`: capture `opsForValue().set(key, roomId, Duration.ofHours(24))`.
- `resolveToken_returns_roomId_when_key_present`
- `resolveToken_returns_null_when_key_expired_or_missing`
- `getInviteUrl_concatenates_baseUrl_and_token`: set `inviteLinkBaseUrl` via field reflection; assert result starts with that base.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.InviteLinkServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/InviteLinkServiceTest.java
git commit -m "test(chat-service): cover InviteLinkService token TTL + resolve"
```

---

## Task 11: DmRoomServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/DmRoomServiceTest.java`

**Mocks:** `ChatRoomRepository`, `RoomCacheEvictor`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `returns_existing_dm_when_pair_already_exists`: stub `findByDmParticipants(u1, u2)` to non-empty; verify no `save`.
- `pair_order_is_normalized`: passing `(u2, u1)` returns same room as `(u1, u2)` (whichever order the service canonicalizes — assert by capturing `save(room)` and inspecting the `dmKey` or whichever canonical field exists).
- `creates_new_dm_with_DIRECT_room_type_when_none_exists`
- `evicts_cache_after_creation`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.DmRoomServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/DmRoomServiceTest.java
git commit -m "test(chat-service): cover DmRoomService idempotency + canonical pair"
```

---

## Task 12: ChatPersistenceServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/ChatPersistenceServiceTest.java`

**Mocks:** `ChatMessageRepository`, `MessageEncryptor`, `OutboxRepository` (or whatever outbox seam exists — check the source).

**Scenarios:**

- [ ] **Step 1: Inspect ChatPersistenceService dependencies first**

```bash
grep -n "private final\|@RequiredArgsConstructor\|public " chat-service/src/main/java/com/chatflow/chat/service/ChatPersistenceService.java | head
```
Lock the mock list to the actual constructor args.

- [ ] **Step 2: Write tests**

- `encrypts_content_before_save_when_encryptor_enabled`: stub `messageEncryptor.isEnabled() == true`; capture saved entity; assert content differs from input.
- `passes_content_through_when_encryptor_disabled`
- `writes_outbox_event_with_chat_messages_topic_key`: capture outbox entry; assert `topic == KafkaTopics.CHAT_MESSAGES`, `key == chatRoomId`.

- [ ] **Step 3/4: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.ChatPersistenceServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/ChatPersistenceServiceTest.java
git commit -m "test(chat-service): cover ChatPersistenceService encrypt + outbox"
```

---

## Task 13: AuditServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/AuditServiceTest.java`

**Mocks:** Logback list-appender or capture via `SimpleMeterRegistry` if AuditService publishes a counter (inspect source).

**Scenarios:**

- [ ] **Step 1: Write tests**

- `logAccess_emits_structured_log_with_userId_roomId_event`: attach a `ListAppender<ILoggingEvent>` to the `AuditService` logger; call `logAccess(...)`; assert one INFO-level message contains all 3 fields.
- `logAccess_handles_null_username`: passing `null` username should not throw.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.AuditServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/AuditServiceTest.java
git commit -m "test(chat-service): cover AuditService structured log emission"
```

---

## Task 14: AiSummaryBroadcastServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/AiSummaryBroadcastServiceTest.java`

**Mocks:** `SimpMessagingTemplate`, plus whatever consumer / repo this service uses (inspect source).

**Scenarios:**

- [ ] **Step 1: Write tests**

- `broadcasts_AI_SUMMARY_on_topic_when_consumed`: feed a `KafkaTopics.AI_SUMMARIES` payload; capture `messagingTemplate.convertAndSend("/topic/chat/" + roomId, payload)`; assert `type == "AI_SUMMARY"`.
- `ignores_malformed_payload_without_throwing`: pass a null/empty roomId; verify no `convertAndSend`.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.AiSummaryBroadcastServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/AiSummaryBroadcastServiceTest.java
git commit -m "test(chat-service): cover AI summary broadcast happy + malformed paths"
```

---

## Task 15: OutboxPollerTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/OutboxPollerTest.java`

**Mocks:** `OutboxRepository`, `KafkaTemplate<String, String>`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `publishes_pending_events_then_marks_them_sent`: stub repo to return 2 pending entries; capture `kafkaTemplate.send(topic, key, payload)` twice; verify `markSent(...)` on both ids.
- `does_nothing_when_no_pending_events`
- `keeps_event_pending_when_kafka_send_throws`: stub send to throw; verify `markSent` never called for that id.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.OutboxPollerTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/OutboxPollerTest.java
git commit -m "test(chat-service): cover OutboxPoller send-then-mark + failure retry"
```

---

## Task 16: OrderEventConsumerTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/OrderEventConsumerTest.java`

**Mocks:** Inspect `OrderEventConsumer` first. Likely needs `ChatRoomService` (get-or-create), `MessageSenderService`, possibly `SimpMessagingTemplate`.

**Scenarios:**

- [ ] **Step 1: Inspect**

```bash
cat chat-service/src/main/java/com/chatflow/chat/service/OrderEventConsumer.java
```

- [ ] **Step 2: Write tests**

- `creates_room_from_externalId_then_publishes_system_message_on_new_order`
- `reuses_existing_room_when_externalId_already_mapped`
- `ignores_unsupported_event_type_without_throwing`

- [ ] **Step 3/4: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.OrderEventConsumerTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/OrderEventConsumerTest.java
git commit -m "test(chat-service): cover OrderEventConsumer room mapping + dispatch"
```

---

## Task 17: ParticipantServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/ParticipantServiceTest.java`

**Mocks:** `StringRedisTemplate`, `ChatRoomRepository`, `RoomCacheEvictor`, `RedisHealthTracker`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `isRoomFull_returns_true_when_unique_user_count_equals_max`: stub `opsForSet().members(...)` to return 10 entries spanning 10 distinct userIds.
- `isRoomFull_dedupes_userIds_across_sessions`: 11 entries but only 9 unique userIds → returns false.
- `syncParticipantCountFromRedis_writes_distinct_userId_count_to_chat_rooms_table`: capture `chatRoomRepository.save(room)`; assert `participantCount` equals unique-user count.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.ParticipantServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/ParticipantServiceTest.java
git commit -m "test(chat-service): cover ParticipantService dedup + count sync"
```

---

## Task 18: RoomCacheEvictorTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/RoomCacheEvictorTest.java`

**Mocks:** `StringRedisTemplate`, `RedisHealthTracker`.

**Scenarios:**

- [ ] **Step 1: Write tests**

- `evict_deletes_room_cache_and_rooms_list_keys`: capture `redisTemplate.delete(key)` calls; assert both `"chatflow:room:" + id` and `"chatflow:rooms:list"` are deleted.
- `evict_noop_when_circuit_open`: verify no `delete` calls.
- `evict_records_redis_failure_on_exception`: stub `delete(...)` to throw; verify `redisHealth.recordFailure(...)` is called once.

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.RoomCacheEvictorTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/RoomCacheEvictorTest.java
git commit -m "test(chat-service): cover RoomCacheEvictor delete keys + circuit-open"
```

---

## Task 19: LinkPreviewServiceTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/LinkPreviewServiceTest.java`

**Mocks:** External HTTP client (likely `RestTemplate` or `OkHttpClient` — inspect source), `StringRedisTemplate`, `RedisHealthTracker`.

**Scenarios:**

- [ ] **Step 1: Inspect**

```bash
grep -n "private final\|RestTemplate\|HttpClient\|WebClient" chat-service/src/main/java/com/chatflow/chat/service/LinkPreviewService.java
```

- [ ] **Step 2: Write tests**

- `returns_cached_preview_when_redis_hit`
- `fetches_and_caches_when_redis_miss`: stub HTTP client to return canned HTML; assert parsed title/description/image match the canned source; capture cache `set(key, json, ttl)`.
- `returns_null_when_http_fails`: stub client to throw; assert no cache write.
- `respects_max_content_size`: stub client to return >limit bytes; assert preview is truncated or null.

- [ ] **Step 3/4: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.LinkPreviewServiceTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/LinkPreviewServiceTest.java
git commit -m "test(chat-service): cover LinkPreviewService HTTP fetch + cache"
```

---

## Task 20: MuteResultTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MuteResultTest.java`

`MuteResult` is a 6-LOC value class — a single sanity test is enough.

- [ ] **Step 1: Inspect**

```bash
cat chat-service/src/main/java/com/chatflow/chat/service/MuteResult.java
```

- [ ] **Step 2: Write tests**

- `factory_methods_produce_expected_shape`: `MuteResult.muted(LocalDateTime.MAX)` → `isMuted() == true`, `mutedUntil()` returns the passed value; `MuteResult.notMuted()` → `isMuted() == false`.
- `equals_and_hashCode_when_record`: only if it's declared `record`. If it's a class without equals, skip and document why.

- [ ] **Step 3/4: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.service.MuteResultTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/service/MuteResultTest.java
git commit -m "test(chat-service): cover MuteResult factory methods"
```

---

## Task 21: PR-1 sanity check — full suite

- [ ] **Step 1: Run full suite**

```bash
./gradlew :chat-service:test --no-daemon
```
Expected: `BUILD SUCCESSFUL`, `tests` count increased by ~50 from the previous 245 baseline (each task ≈ 3–5 tests, ×19 = ~70 max).

- [ ] **Step 2: Confirm tests=300+**

```bash
python3 -c "
import re, glob
total=fails=errors=skipped=0
for f in glob.glob('chat-service/build/test-results/test/TEST-*.xml'):
    with open(f) as fh: head = fh.read(2000)
    m = re.search(r'tests=\"(\d+)\"\s+skipped=\"(\d+)\"\s+failures=\"(\d+)\"\s+errors=\"(\d+)\"', head)
    if m:
        total += int(m.group(1)); skipped += int(m.group(2))
        fails += int(m.group(3)); errors += int(m.group(4))
print(f'tests={total} failures={fails} errors={errors} skipped={skipped}')
"
```
Expected: `tests` ≥ 300, `failures=0 errors=0`.

---

## Task 22: PR-1 code review

- [ ] **Step 1: Invoke superpowers:code-reviewer agent**

Prompt to the reviewer:
> Review branch `test/stage-1-services` against `develop`. Focus on:
> 1. Each new test uses `@ExtendWith(MockitoExtension.class)` + manual constructor wiring per the `MessageSenderServiceMuteGateTest` template.
> 2. No `@SpringBootTest`, no slice tests.
> 3. `@Nested` groupings used for scenario families.
> 4. `verify(...)` / `ArgumentCaptor` for side-effect assertions, not field reads.
> 5. Each touched service has a happy-path test and at least one error/edge case.
> Report blocking issues; allow merge only when none remain.

- [ ] **Step 2: Address reviewer feedback (if any)**

Loop: fix → re-run reviewer → no blockers.

- [ ] **Step 3: Push branch**

```bash
git push -u origin test/stage-1-services
```

---

## Task 23: Merge PR-1 into develop

- [ ] **Step 1: Merge**

```bash
git checkout develop && git pull --ff-only origin develop
git merge --no-ff test/stage-1-services -m "Merge test/stage-1-services into develop

19 service tests added per Stage 1 plan (safety net for Stages 2-3).
Backend test count: 245 → ~310+. All Mockito-style, no Spring context."
git push origin develop
```

- [ ] **Step 2: Verify develop-build.yml succeeds**

```bash
gh run list --workflow=develop-build.yml --limit 1
```
Expected: latest run `completed	success`.

---

# PR-2 — Controller & Guard tests (6 units)

**Branch:** `test/stage-1-controllers` (off the now-merged `develop`).
**Reviewer agent:** `superpowers:code-reviewer` MUST run before merging into `develop`.

## Task 24: Branch off develop

- [ ] **Step 1: Branch**

```bash
git checkout develop && git pull --ff-only origin develop
git checkout -b test/stage-1-controllers
```

---

## Task 25: ChatRoomControllerTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/ChatRoomControllerTest.java`

**Approach:** `MockMvc.standaloneSetup` per `FcmControllerTest`. Mock every collaborator on `ChatRoomController` (`ChatRoomService`, `RoomMembershipService`, `MessageReadService`, `DmRoomService`, `AuditService`, `StringRedisTemplate`, `RoomVisibilityService`, `MessageSenderService`, `RoomMembershipGuard`).

**Endpoint coverage** — one happy + one auth/permission failure per endpoint:

- [ ] **Step 1: Write tests**

`@Nested GetAllRooms`:
- `returns_all_rooms_when_no_userId_header`: stub `chatRoomService.getAllRooms()` to a 2-item list; expect 200 + `$.data.length == 2`.
- `filters_hidden_rooms_when_userId_present_and_visibility_returns_subset`

`@Nested GetRoom`:
- `200_when_guard_passes_and_room_exists`: stub `membershipGuard.requireMember(roomId, "u1") == null`; stub `chatRoomService.getRoom(roomId)` to present.
- `403_when_guard_returns_forbidden`: stub guard to return a 403 `ResponseEntity`.
- `404_when_room_missing_but_guard_passed`

`@Nested CreateRoom`:
- `401_when_X-User-Id_missing`
- `201_with_created_room_when_request_valid`

`@Nested DeleteRoom`:
- `403_when_createdBy_does_not_match_userId`
- `403_when_createdBy_is_null_legacy_room`: this is the explicit policy from QA pass 2.
- `200_when_owner`

`@Nested UpdateRoomSettings`:
- `403_when_not_owner`
- `200_when_owner_updates_name_only`

`@Nested HideRoom`:
- `400_when_room_is_not_DIRECT`
- `200_when_DIRECT_and_user_authenticated`

- [ ] **Step 2: Run**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.controller.ChatRoomControllerTest --no-daemon
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/test/java/com/chatflow/chat/controller/ChatRoomControllerTest.java
git commit -m "test(chat-service): cover ChatRoomController CRUD + ownership gates"
```

---

## Task 26: RoomMembershipGuardTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/RoomMembershipGuardTest.java`

**Mocks:** `ChatRoomService`, `RoomMembershipService`, `RoomMemberRepository`.

- [ ] **Step 1: Write tests**

- `returns_401_when_userId_null_or_blank`
- `returns_null_when_user_is_in_room_members`: stub `roomMemberRepository.existsByRoomIdAndUserId(...)` to `true`.
- `returns_null_and_backfills_OWNER_when_userId_equals_createdBy_legacy`: stub repo `false`; stub `chatRoomService.getRoom(...)` to room with `createdBy == userId`; verify `roomMembershipService.addMemberIfAbsent(roomId, userId, null, OWNER)` is called exactly once; result == `null`.
- `returns_403_when_not_member_and_not_creator`

- [ ] **Step 2: Run**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.controller.RoomMembershipGuardTest --no-daemon
```

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/test/java/com/chatflow/chat/controller/RoomMembershipGuardTest.java
git commit -m "test(chat-service): cover RoomMembershipGuard auth + legacy bridge"
```

---

## Task 27: RoomReadStateControllerTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/RoomReadStateControllerTest.java`

**Mocks:** `ChatRoomService`, `UnreadCountService`, `ReadReceiptService`, `StringRedisTemplate`, `RoomMembershipGuard`.

- [ ] **Step 1: Write tests**

`@Nested UnreadCounts`:
- `200_with_empty_map_when_no_userId_header`
- `200_with_per_room_counts_when_userId_present`

`@Nested Readers`:
- `403_when_guard_blocks`
- `200_with_userId_to_lastReadMessageId_map_when_member`

`@Nested LastRead`:
- `getLastRead_200_with_empty_when_no_userId_header`
- `putLastRead_calls_markRead_when_lastReadMessageId_provided`
- `putLastRead_calls_updateReadAt_only_when_lastReadMessageId_blank`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.controller.RoomReadStateControllerTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/controller/RoomReadStateControllerTest.java
git commit -m "test(chat-service): cover RoomReadStateController unread + last-read"
```

---

## Task 28: RoomInviteControllerTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/RoomInviteControllerTest.java`

**Mocks:** `ChatRoomService`, `RoomMembershipService`, `InviteLinkService`, `ParticipantService`, `StringRedisTemplate`, `RoomMembershipGuard`.

- [ ] **Step 1: Write tests**

`@Nested InviteUser`:
- `404_when_room_not_found`
- `400_when_allowInvites_is_false`
- `400_when_room_full`
- `400_when_targetUsername_already_a_participant`
- `200_and_sendInviteMessage_called_on_success`

`@Nested CreateInviteLink`:
- `403_when_guard_blocks`: explicit Stage-1 lock-in of QA pass 3 policy.
- `403_when_allowInvites_false_even_for_member`
- `200_with_token_and_url_on_success`

`@Nested JoinByInvite`:
- `401_when_no_userId`
- `410_when_token_expired`
- `400_when_room_full`
- `200_and_addMemberIfAbsent_called_on_success`

- [ ] **Step 2/3: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.controller.RoomInviteControllerTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/controller/RoomInviteControllerTest.java
git commit -m "test(chat-service): cover RoomInviteController membership + invite-link flow"
```

---

## Task 29: ChatControllerTest

`ChatController` handles STOMP messages (`@MessageMapping`), not REST. MockMvc won't reach it — use direct method calls with a stubbed `SimpMessageHeaderAccessor`.

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/ChatControllerTest.java`

**Mocks:** all collaborators on `ChatController` (`UserPresenceService`, `MessageSenderService`, `RoomMembershipService` if it now flows through here, `RoomMemberRepository`, `ChatRoomService`, etc. — inspect source).

- [ ] **Step 1: Inspect**

```bash
grep -n "private final\|@MessageMapping" chat-service/src/main/java/com/chatflow/chat/controller/ChatController.java
```

- [ ] **Step 2: Write tests**

- `sendMessage_rejects_non_member_via_isMember_gate`: stub membership check to false; verify `messageSenderService.send(...)` never called.
- `sendMessage_passes_through_when_member`
- `addUser_calls_userPresenceService_join`
- `typing_publishes_TYPING_event_only_when_member`
- `markRead_calls_read_receipt_only_when_member`

- [ ] **Step 3/4: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.controller.ChatControllerTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/controller/ChatControllerTest.java
git commit -m "test(chat-service): cover ChatController STOMP membership gates"
```

---

## Task 30: MentionDigestControllerTest

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/MentionDigestControllerTest.java`

**Mocks:** `MentionDigestService`.

- [ ] **Step 1: Inspect**

```bash
cat chat-service/src/main/java/com/chatflow/chat/controller/MentionDigestController.java
```

- [ ] **Step 2: Write tests**

- `401_when_X-User-Id_missing_on_get`
- `200_with_list_when_authenticated`
- `acknowledge_endpoint_calls_service_with_userId_and_returns_200`

- [ ] **Step 3/4: Run + commit**

```bash
./gradlew :chat-service:test --tests com.chatflow.chat.controller.MentionDigestControllerTest --no-daemon
git add chat-service/src/test/java/com/chatflow/chat/controller/MentionDigestControllerTest.java
git commit -m "test(chat-service): cover MentionDigestController auth + ack"
```

---

## Task 31: PR-2 sanity check — full suite

- [ ] **Step 1: Run**

```bash
./gradlew :chat-service:test --no-daemon
```
Expected: `BUILD SUCCESSFUL`, total tests ≥ 350 (PR-1's ~310 + ~40 controller tests).

- [ ] **Step 2: Confirm with parser**

```bash
python3 -c "
import re, glob
total=fails=errors=skipped=0
for f in glob.glob('chat-service/build/test-results/test/TEST-*.xml'):
    with open(f) as fh: head = fh.read(2000)
    m = re.search(r'tests=\"(\d+)\"\s+skipped=\"(\d+)\"\s+failures=\"(\d+)\"\s+errors=\"(\d+)\"', head)
    if m:
        total += int(m.group(1)); skipped += int(m.group(2))
        fails += int(m.group(3)); errors += int(m.group(4))
print(f'tests={total} failures={fails} errors={errors} skipped={skipped}')
"
```
Expected: `tests=350+ failures=0 errors=0`. This is the **Stage 1 exit gate** — if tests are under 350, audit which tasks shipped fewer assertions than estimated and top up before merging.

---

## Task 32: PR-2 code review

- [ ] **Step 1: Invoke superpowers:code-reviewer**

Prompt:
> Review branch `test/stage-1-controllers` against `develop`. Focus on:
> 1. `MockMvc.standaloneSetup` with `GlobalExceptionHandler` wired (FcmControllerTest model).
> 2. Auth (`X-User-Id` header) handling tested per endpoint.
> 3. ChatController STOMP tests bypass MockMvc and use direct method calls — this is intentional.
> 4. RoomMembershipGuardTest pins the legacy-createdBy bridge contract (Stages 3-A will lean on this).
> Report blockers.

- [ ] **Step 2: Address feedback**

- [ ] **Step 3: Push**

```bash
git push -u origin test/stage-1-controllers
```

---

## Task 33: Merge PR-2 into develop

- [ ] **Step 1: Merge**

```bash
git checkout develop && git pull --ff-only origin develop
git merge --no-ff test/stage-1-controllers -m "Merge test/stage-1-controllers into develop

6 controller/guard tests added. Stage 1 (safety net) complete.
Backend tests: 245 → 350+. Stages 2 (frontend decomp) and 3
(backend patterns) can now proceed against a test floor."
git push origin develop
```

- [ ] **Step 2: Verify develop-build.yml green**

```bash
gh run list --workflow=develop-build.yml --limit 1
```
Expected: `completed	success`.

- [ ] **Step 3: Announce stage close**

Update the spec's status to "Stage 1 complete; Stage 2 may begin" and proceed to write the Stage 2 plan in the next planning cycle.

---

## Stage 1 close-out checklist

- [ ] PR-1 merged on develop, develop-build.yml green.
- [ ] PR-2 merged on develop, develop-build.yml green.
- [ ] `tests=350+ failures=0 errors=0` on develop tip.
- [ ] Every one of the 25 listed units has at least one test file under `chat-service/src/test/...`.
- [ ] Spec doc `docs/superpowers/specs/2026-05-24-refactor-three-stage-design.md` Status updated to "Stage 1 complete".

Next: spawn writing-plans again for Stage 2 (frontend decomposition) when the user is ready.
