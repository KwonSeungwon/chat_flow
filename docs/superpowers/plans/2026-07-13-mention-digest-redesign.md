# Mention Digest Redesign Implementation Plan

> **STATUS: ✅ COMPLETE (2026-07-13).** All 6 tasks implemented on `refactor/mention-redesign` via subagent-driven development. Deviations from this plan, found during implementation/review: chat_messages column is `is_deleted` (not `deleted`); `LEFT(username, 50)` guards added in the V11 backfill; the redundant `idx_message_mentions_message` index dropped (unique-index prefix covers it); the plan's `@under_score.` test fixture was wrong (`.` is in the mention character class); retention purge extended to delete mention rows with the same cutoff (whole-branch review finding). Follow-up filed: mention re-extraction on message edit (design decision). 622 tests green across all modules; frontend untouched as designed.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mention feature's `content LIKE '%@user%'` full-table scan with a structured `message_mentions` table populated at send time — fixing a correctness bug (mentions silently return nothing when at-rest encryption is enabled, because LIKE matches ciphertext), an information leak (no room-membership scoping — anyone can read 140-char previews from rooms they don't belong to), an unbounded Redis read-set, and a hot-path performance problem (leading-wildcard LIKE over all messages on every digest/unread-count call).

**Architecture:** One new table (`V11`), one new JPA entity+repository, one shared `MentionExtractor` (unifying the two divergent @-grammars in `MessageSenderService` and `MessageEventListener`), rows inserted inside the existing persist transaction and scoped to room members at send time, and a rewrite of `MentionDigestService` onto the new repository with read-state as a column (Redis read-set deleted). **The REST API contract (`MentionItemDto` shape + 4 endpoints) is unchanged — the frontend needs zero changes.** Content previews are NOT stored in the mention row; they are joined from `chat_messages` and decrypted at read time, preserving at-rest encryption.

**Tech Stack:** Spring Boot 3.2 / Java 17 / JPA / Flyway (PostgreSQL-only; test profile uses H2 create-drop so new columns auto-apply in tests) / JUnit5 + AssertJ + Mockito.

**Out of scope:** migrating existing Redis read-set entries (backfilled rows are inserted `read=true` — see Decisions); Testcontainers migration testing (Tier 3 enabler, separate); FCM notification content changes.

**Decisions already made (do NOT revisit):**
- **Mention grammar = the `MessageEventListener` pattern** `@([A-Za-z0-9_\.가-힣]{1,30})` (Korean-aware, bounded), not `MessageSenderService`'s sloppy `@(\S+)`. Extracted candidates are then filtered against actual room members, which self-corrects residual grammar looseness.
- **Member-scoped at send time:** a mention row is only created if the mentioned username is a `room_members` row of that room at send time. This is the info-leak fix — you cannot receive (or read previews of) mentions from rooms you're not in.
- **Read state lives on the row** (`read` boolean), keyed by `mentioned_user_id`. The Redis set `chatflow:mentions:read:{userId}` (unbounded, no TTL) is deleted.
- **No stored preview:** `MentionItemDto.contentPreview` is built at read time by joining `chat_messages` and decrypting via `MessageEncryptor` (same pattern as `MessageReadService`). Storing plaintext previews would defeat at-rest encryption.
- **Backfill inserts `read=true`:** plaintext history (up to 365 days) is backfilled so the mentions screen stays populated, but marked read to avoid an unread-badge storm at deploy; pending unread state from the old Redis set is intentionally dropped (one-time, low-stakes). Under encryption-on the backfill matches nothing and is a harmless no-op.
- **Self-mentions excluded** (`m.username <> rm.username`), matching the current `findMentionsOf` behavior.

**Files (final state):**
- Create: `chat-service/src/main/resources/db/migration/V11__message_mentions.sql`
- Create: `chat-service/src/main/java/com/chatflow/chat/entity/MessageMentionEntity.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/repository/MessageMentionRepository.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/service/message/MentionExtractor.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/message/MessageSenderService.java` (insert rows + use extractor for FCM)
- Modify: `chat-service/src/main/java/com/chatflow/chat/event/MessageEventListener.java` (use shared extractor)
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/notification/MentionDigestService.java` (rewrite onto the new repo)
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java` (delete `findMentionsOf`)
- Tests: `MentionExtractorTest`, `MessageMentionWriteTest` (send-path), `MentionDigestServiceTest` (rewrite)

---

## Task 1: V11 migration — `message_mentions` table + plaintext backfill

**Files:**
- Create: `chat-service/src/main/resources/db/migration/V11__message_mentions.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V11: 구조화된 멘션 테이블.
-- 기존 findMentionsOf는 content LIKE '%@user%' 전체 스캔이었고,
-- (a) 암호화 활성 시 ciphertext를 매칭해 아무것도 못 찾는 정합성 버그,
-- (b) 방 멤버십 미검증 정보 누출, (c) leading-wildcard 성능 문제가 있었다.
-- 이제 멘션은 발신 시점에 방 멤버로 한정해 행으로 기록된다.
CREATE TABLE IF NOT EXISTS message_mentions (
    id                 BIGSERIAL PRIMARY KEY,
    message_id         VARCHAR(36)  NOT NULL,
    room_id            VARCHAR(50)  NOT NULL,
    mentioned_user_id  VARCHAR(36)  NOT NULL,
    mentioned_username VARCHAR(50)  NOT NULL,
    from_username      VARCHAR(50)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    read               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_message_mentions UNIQUE (message_id, mentioned_user_id),
    CONSTRAINT fk_message_mentions_room FOREIGN KEY (room_id)
        REFERENCES chat_rooms(id) ON DELETE CASCADE
);

-- 디이제스트 조회(list/unread-count)는 항상 mentioned_user_id + created_at 기준.
CREATE INDEX IF NOT EXISTS idx_message_mentions_user_created
    ON message_mentions (mentioned_user_id, created_at DESC);
-- 메시지 삭제 전파 시 message_id 로 제거.
CREATE INDEX IF NOT EXISTS idx_message_mentions_message
    ON message_mentions (message_id);

-- 평문 히스토리 백필 (최근 365일). 암호화가 켜져 있던 기간의 content는
-- ciphertext라 매칭되지 않음(무해한 no-op). read=true로 넣어 배지 폭주를 막는다
-- (멘션 화면에는 계속 보인다). 자기 멘션 제외, 삭제/AI 메시지 제외.
INSERT INTO message_mentions
    (message_id, room_id, mentioned_user_id, mentioned_username, from_username, created_at, read)
SELECT m.message_id,
       m.chat_room_id,
       rm.user_id,
       rm.username,
       m.username,
       m.timestamp,
       TRUE
FROM chat_messages m
JOIN room_members rm
  ON rm.room_id = m.chat_room_id
 AND m.content LIKE '%@' || rm.username || '%'
 AND rm.username <> m.username
WHERE m.type = 'CHAT'
  AND m.deleted = FALSE
  AND m.is_ai_generated = FALSE
  AND m.timestamp >= NOW() - INTERVAL '365 days'
ON CONFLICT (message_id, mentioned_user_id) DO NOTHING;
```

- [ ] **Step 2: Verify column names against the entity** — `chat_messages` columns are snake_case (`chat_room_id`, `is_ai_generated`); confirm with `grep -n '@Column' chat-service/src/main/java/com/chatflow/chat/entity/ChatMessageEntity.java` and adjust if any name differs.
- [ ] **Step 3: Commit** — `git add` the migration; commit `feat(chat-service): V11 message_mentions table + plaintext backfill` with a `Constraint: backfill is read=true (no unread storm); no-op under encryption` trailer.

---

## Task 2: Entity + repository

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/entity/MessageMentionEntity.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/repository/MessageMentionRepository.java`

- [ ] **Step 1: Entity** (match the codebase's Lombok style — `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor @Entity`, NOT `@Data`):

```java
package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_mentions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "mentioned_user_id"}))
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class MessageMentionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "room_id", nullable = false, length = 50)
    private String roomId;

    @Column(name = "mentioned_user_id", nullable = false, length = 36)
    private String mentionedUserId;

    @Column(name = "mentioned_username", nullable = false, length = 50)
    private String mentionedUsername;

    @Column(name = "from_username", nullable = false, length = 50)
    private String fromUsername;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read", nullable = false)
    private boolean read;
}
```

- [ ] **Step 2: Repository**:

```java
package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageMentionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageMentionRepository extends JpaRepository<MessageMentionEntity, Long> {

    List<MessageMentionEntity> findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String mentionedUserId, LocalDateTime since);

    long countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
            String mentionedUserId, LocalDateTime since);

    @Modifying
    @Query("UPDATE MessageMentionEntity m SET m.read = true " +
           "WHERE m.mentionedUserId = :userId AND m.messageId = :messageId")
    int markRead(@Param("userId") String userId, @Param("messageId") String messageId);

    @Modifying
    @Query("UPDATE MessageMentionEntity m SET m.read = true " +
           "WHERE m.mentionedUserId = :userId AND m.read = false AND m.createdAt >= :since")
    int markAllRead(@Param("userId") String userId, @Param("since") LocalDateTime since);
}
```

- [ ] **Step 3: Compile + full chat tests** — `./gradlew :chat-service:test` (H2 create-drop picks the entity up automatically). Expect green.
- [ ] **Step 4: Commit** — `feat(chat-service): MessageMentionEntity + repository`.

---

## Task 3: Shared MentionExtractor (unify the two grammars)

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/service/message/MentionExtractor.java`
- Test: `chat-service/src/test/java/com/chatflow/chat/service/message/MentionExtractorTest.java`

- [ ] **Step 1: Failing test first**:

```java
package com.chatflow.chat.service.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MentionExtractorTest {

    @Test
    void extracts_ascii_korean_and_underscore_names() {
        assertThat(MentionExtractor.extract("hi @bob and @김간호사, also @under_score."))
                .containsExactly("bob", "김간호사", "under_score");
    }

    @Test
    void deduplicates_and_preserves_order() {
        assertThat(MentionExtractor.extract("@a @b @a")).containsExactly("a", "b");
    }

    @Test
    void empty_when_no_at_or_null() {
        assertThat(MentionExtractor.extract(null)).isEmpty();
        assertThat(MentionExtractor.extract("no mentions here")).isEmpty();
    }

    @Test
    void caps_name_length_at_30() {
        String longName = "a".repeat(31);
        // pattern matches only the first 30 chars
        assertThat(MentionExtractor.extract("@" + longName))
                .containsExactly("a".repeat(30));
    }
}
```

Run: `./gradlew :chat-service:test --tests 'com.chatflow.chat.service.message.MentionExtractorTest'` — FAILS (class missing).

- [ ] **Step 2: Implement** (move `MessageEventListener`'s pattern here verbatim — it becomes the single grammar):

```java
package com.chatflow.chat.service.message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단일 @멘션 문법. MessageSenderService(FCM)와 MessageEventListener
 * (UNREAD_INCREMENT)가 서로 다른 정규식을 쓰던 것을 통일한다.
 * 추출 결과는 후보일 뿐이며, 호출자가 room_members와 대조해 확정한다.
 */
public final class MentionExtractor {

    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([A-Za-z0-9_\\.\\uac00-\\ud7a3]{1,30})");

    private MentionExtractor() {}

    public static List<String> extract(String content) {
        if (content == null || content.indexOf('@') < 0) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher m = MENTION_PATTERN.matcher(content);
        while (m.find()) names.add(m.group(1));
        return List.copyOf(names);
    }
}
```

- [ ] **Step 3: Test passes.** Then switch `MessageEventListener.extractMentionedUsernames` to delegate to `MentionExtractor.extract` (delete its private pattern), and switch `MessageSenderService`'s FCM mention loop from its `@(\S+)` `MENTION_PATTERN` to `MentionExtractor.extract` (delete the field). NOTE: this slightly narrows the FCM grammar (e.g. `@bob!` matched `bob!` before, now `bob`) — that is the intended unification; existing `MessageSenderService`/FCM tests may need their fixture usernames adjusted if any used non-word characters.
- [ ] **Step 4: Full `:chat-service:test` green.**
- [ ] **Step 5: Commit** — `refactor(chat-service): single MentionExtractor grammar for FCM + unread flags`.

---

## Task 4: Write mention rows at send time (member-scoped, in the persist TX)

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/message/MessageSenderService.java`
- Test: `chat-service/src/test/java/com/chatflow/chat/service/message/MessageMentionWriteTest.java` (new)

**Design:** after `chatPersistenceService.persistMessageAndPublish(...)` (which is `@Transactional` on the *persistence service* — the sender itself is not transactional), insert mention rows via a small transactional method. To keep atomicity with the message row, add the mention insertion INTO `ChatPersistenceService.persistMessageAndPublish` — it already owns the TX. Pass the resolved mentions in.

- [ ] **Step 1: Failing test** — Mockito on `MessageSenderService`: a CHAT message with content `"hey @bob"` where `roomMemberRepository` (or the chosen lookup) says bob IS a member with userId `bob-id` → verify a `MessageMentionEntity` with (messageId, roomId, mentionedUserId=bob-id, read=false) is persisted; content `"hey @stranger"` (not a member) → verify NO row; sender self-mention `"@me"` → NO row; FILE/JOIN types → NO rows.
- [ ] **Step 2: Implement:**
  - In `MessageSenderService.send(...)`, after room enrichment and before/alongside the persist call: `List<String> candidates = MentionExtractor.extract(message.getContent())` (only for `MessageType.CHAT`).
  - Resolve candidates to members: `roomMemberRepository.findByRoomIdAndUsernameIn(roomId, candidates)` (add this derived finder to `RoomMemberRepository`), filter out the sender's own username, map to `MessageMentionEntity` builders (`createdAt = message.getTimestamp()`, `read=false`).
  - Extend `ChatPersistenceService.persistMessageAndPublish` with a `List<MessageMentionEntity> mentions` parameter (nullable/empty OK) and `messageMentionRepository.saveAll(mentions)` inside the existing `@Transactional` — atomic with the message + outbox rows. Update the existing callers (grep — `MessageSenderService` is the only one for this overload; keep a backward-compatible overload if others exist).
- [ ] **Step 3: Reuse, don't re-query:** the FCM mention loop in `send` should reuse the same resolved member list (push only to actual members now — this also fixes FCM mention spam to non-members).
- [ ] **Step 4: Tests green (focused + full).**
- [ ] **Step 5: Commit** — `feat(chat-service): record member-scoped mention rows in the persist transaction`.

---

## Task 5: Rewrite MentionDigestService onto the new table

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/notification/MentionDigestService.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java` (delete `findMentionsOf`)
- Modify: `chat-service/src/main/java/com/chatflow/chat/dto/MentionItemDto.java` (add a factory from the new entity + decrypted content)
- Test: rewrite/extend `MentionDigestServiceTest`

**API contract stays identical** (`MentionItemDto(messageId, chatRoomId, fromUsername, contentPreview, timestamp, read)`; endpoints unchanged).

- [ ] **Step 1: Failing tests** — mock `MessageMentionRepository` + `ChatMessageRepository` + `MessageEncryptor`:
  - `list`: returns rows mapped to DTOs; preview comes from the joined `chat_messages.content`, decrypted when `messageEncryptor.isEnabled()`, truncated to 140; deleted messages excluded (preview source row `deleted=true` → skip the mention).
  - `unreadCount`: delegates to `countByMentionedUserIdAndReadFalseAndCreatedAtAfter`.
  - `markRead`/`markAllRead`: call the `@Modifying` queries; **no Redis interaction at all** (`verifyNoInteractions(redisTemplate)` — then delete the field).
- [ ] **Step 2: Implement:**

```java
public List<MentionItemDto> list(String userId, String username, int days) {
    LocalDateTime since = LocalDateTime.now().minusDays(clamp(days));
    List<MessageMentionEntity> rows =
            mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, since);
    if (rows.isEmpty()) return List.of();
    Map<String, ChatMessageEntity> messages = chatMessageRepository
            .findAllById(rows.stream().map(MessageMentionEntity::getMessageId).toList())
            .stream().collect(Collectors.toMap(ChatMessageEntity::getMessageId, Function.identity()));
    return rows.stream()
            .map(r -> {
                ChatMessageEntity msg = messages.get(r.getMessageId());
                if (msg == null || msg.isDeleted()) return null;   // deleted → drop from digest
                String plain = messageEncryptor.isEnabled()
                        ? messageEncryptor.decrypt(msg.getContent()) : msg.getContent();
                return MentionItemDto.of(r, preview(plain));
            })
            .filter(Objects::nonNull)
            .toList();
}
```

  (`preview` = 140-char truncation, same rule as the old `MentionItemDto.from`; `MentionItemDto.of(row, preview)` is the new factory — keep the record shape identical.) `unreadCount`, `markRead(userId, messageId)`, `markAllRead(userId, username, days)` map 1:1 onto the repository methods; `markRead`/`markAllRead` need `@Transactional`.
- [ ] **Step 3: Delete** the Redis read-set code (`readKey`, `readSet`, the `StringRedisTemplate` field if now unused) and `ChatMessageRepository.findMentionsOf`. Grep for other `findMentionsOf` callers first (should be only MentionDigestService).
- [ ] **Step 4: Update `MentionDigestController`** only if a signature changed (list/markAllRead still take userId+username+days — keep signatures; `username` may become unused in the service, keep the parameter for API stability and note it).
- [ ] **Step 5: Full `:chat-service:test` green.**
- [ ] **Step 6: Commit** — `refactor(chat-service): mention digest reads message_mentions; drop LIKE scan + Redis read-set` with `Constraint: MentionItemDto shape and endpoints unchanged (frontend untouched)`.

---

## Task 6: Delete-propagation + final QA

- [ ] **Step 1:** Mentions of deleted messages: rows are dropped at read time (Task 5) and the FK cascades on room deletion. For message deletion, add one line to `MessageEditService.deleteMessage` inside the TX: `messageMentionRepository.deleteByMessageId(messageId)` (add the derived `@Modifying` method). Test: delete → mention rows gone.
- [ ] **Step 2:** Run the full backend: `./gradlew test` — all modules green.
- [ ] **Step 3:** API-contract check (chatflow-qa style): `GET /api/chat/mentions` response JSON keys unchanged (`messageId, chatRoomId, fromUsername, contentPreview, timestamp, read`) — assert via the existing controller test or a serialization test.
- [ ] **Step 4:** Commit + whole-branch review before merge.

---

## Self-review notes

- **Spec coverage:** correctness-under-encryption (rows written from plaintext at send time; previews decrypted at read time) ✓; info-leak (member-scoped inserts) ✓; unbounded Redis set (deleted; read column) ✓; LIKE performance (indexed lookups) ✓; grammar unification (Task 3) ✓; backfill (Task 1, read=true, encryption no-op) ✓; frontend untouched (DTO/endpoints identical) ✓.
- **Types consistent:** `MessageMentionRepository` method names match derived-query rules against the entity fields (`mentionedUserId`, `createdAt`, `read`); `MentionItemDto.of` is new but keeps the record components identical.
- **Known small holes:** (a) `markAllRead` uses `days` clamp like today (365 max) — mentions older than the window stay unread (same as old behavior); (b) mention rows for users who later leave the room remain visible in their digest (mention was legitimately addressed while a member — acceptable; delete-on-leave would need a leaveRoom hook, deliberately not added, YAGNI); (c) H2 test profile: `read` is not a reserved word issue in H2/Postgres as a quoted column via JPA — if H2 chokes on `read`, rename the column to `is_read` in V11+entity (checkpoint in Task 2 Step 3).
