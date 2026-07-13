# Unread-Count Durable Cursor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 24h-TTL Redis `chatflow:readat:*` unread cursor with a durable `room_members.last_read_at` column — eliminating the TTL cliff (cursor expiry silently degrades to counting all messages since 2000) AND collapsing the per-room COUNT queries into one grouped JPQL query (the cursor comparison moves into SQL, which is what made batching impossible before).

**Architecture:** V12 adds `last_read_at TIMESTAMP NULL` to `room_members` (backfilled `NOW()` so nobody gets an unread-badge storm at deploy). `ReadReceiptService.markRead/updateReadAt` write-through the DB cursor (single PK-row UPDATE) instead of the Redis readat keys. `UnreadCountService` becomes one grouped query joining `room_members`: `m.timestamp > COALESCE(rm.lastReadAt, rm.joinedAt)` — NULL cursor means "unread since I joined", the correct semantic for a new member. The Redis readat keys die entirely (writer and only reader both change); the `chatflow:read:{roomId}` positions HASH (per-message receipts display) is UNTOUCHED.

**Tech Stack:** Spring Boot 3.2 / Java 17 / JPA / Flyway (PostgreSQL prod, H2 create-drop tests — COALESCE is standard JPQL, no Testcontainers needed).

**Intentional behavior changes (approve-once, do not re-litigate):**
1. **No more TTL cliff:** cursors persist forever; the epoch-2000 fallback path is deleted.
2. **Non-member rooms count 0:** today a room the user never joined falls into the "no cursor" path and shows its entire (retention-window) history as unread; after this change the join produces no row → 0. More correct (no membership = no unread) and kills badge noise on public rooms.
3. **New members count from `joined_at`:** messages sent before joining don't count as unread (previously: everything since 2000 until the first read).
4. **Migration baseline = all-read:** V12 backfills `last_read_at = NOW()`; pending unread state from the old Redis keys is dropped once (same philosophy as the mention backfill — no badge storm).

**Files (final state):**
- Create: `chat-service/src/main/resources/db/migration/V12__room_members_last_read_at.sql`
- Modify: `chat-service/.../entity/RoomMemberEntity.java` (+lastReadAt)
- Modify: `chat-service/.../repository/RoomMemberRepository.java` (+`touchLastReadAt` bulk update)
- Modify: `chat-service/.../repository/ChatMessageRepository.java` (+cursor-join grouped count; DELETE `countNewChatMessages` + `countNewChatMessagesBatch` if unreferenced afterwards)
- Modify: `chat-service/.../service/read/ReadReceiptService.java` (cursor write-through, readat writes deleted)
- Modify: `chat-service/.../service/read/UnreadCountService.java` (single-query rewrite; Redis + RedisHealthTracker deps dropped)
- Tests: `UnreadCountServiceTest` rewrite, `ReadReceiptServiceTest` update, repo test extension.

---

## Task U1: V12 migration + entity + queries

- [ ] **Step 1: V12 migration**

```sql
-- V12: 내구성 있는 unread 커서. Redis chatflow:readat:* (24h TTL)은 만료 시
-- "2000년 이후 전체 카운트"로 조용히 퇴화하는 절벽이 있었다. 커서를
-- room_members 로 옮기면 만료가 없고, cutoff 비교가 SQL 안으로 들어와
-- 방마다 돌던 COUNT 를 단일 그룹 쿼리로 배치할 수 있다.
ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMP NULL;

-- 배포 기준선: 전원 all-read 로 시작 (배지 폭주 방지 — 멘션 백필과 동일 철학).
-- 이후 NULL 은 "가입 후 아직 읽지 않음" = joined_at 폴백 의미로만 쓰인다.
UPDATE room_members SET last_read_at = NOW() WHERE last_read_at IS NULL;
```

- [ ] **Step 2: Entity** — add to `RoomMemberEntity` (nullable, after `joinedAt`):

```java
@Column(name = "last_read_at")
private LocalDateTime lastReadAt;
```

- [ ] **Step 3: Repository queries**

`RoomMemberRepository` (+bulk cursor touch — bulk JPQL so no entity load, mirrors markRead style):

```java
@Modifying
@Transactional
@Query("UPDATE RoomMemberEntity rm SET rm.lastReadAt = :at " +
       "WHERE rm.roomId = :roomId AND rm.userId = :userId")
int touchLastReadAt(@Param("roomId") String roomId,
                    @Param("userId") String userId,
                    @Param("at") LocalDateTime at);
```

`ChatMessageRepository` (+the batched cursor-join count — JPQL theta join, H2/Postgres portable):

```java
@Query("SELECT m.chatRoomId, COUNT(m) FROM ChatMessageEntity m, RoomMemberEntity rm " +
       "WHERE rm.userId = :userId AND rm.roomId = m.chatRoomId " +
       "AND m.chatRoomId IN :roomIds " +
       "AND m.timestamp > COALESCE(rm.lastReadAt, rm.joinedAt) " +
       "AND m.type = 'CHAT' AND m.deleted = false " +
       "GROUP BY m.chatRoomId")
List<Object[]> countUnreadByCursor(@Param("userId") String userId,
                                   @Param("roomIds") List<String> roomIds);
```

- [ ] **Step 4:** `./gradlew :chat-service:test` green (H2 validates the JPQL at context load via existing @DataJpaTest configs). Add a @DataJpaTest case in the existing repo-test style: seed 2 rooms + members with different cursors + messages around the cursors → assert per-room counts; a room where the user is NOT a member → absent from results; NULL cursor → counts from joinedAt.
- [ ] **Step 5: Commit** `feat(chat-service): V12 durable read cursor on room_members + batched unread query`.

## Task U2: Service rewiring

- [ ] **Step 1 (TDD): failing tests** — `UnreadCountServiceTest` rewrite: `getUnreadCounts` issues ONE repository call (`countUnreadByCursor`), initializes all requested roomIds to 0L, overlays returned counts, preserves roomIds order; empty roomIds → empty map, no query; NO Redis/RedisHealthTracker usage (constructor takes only `ChatMessageRepository`). `ReadReceiptServiceTest`: markRead + updateReadAt call `roomMemberRepository.touchLastReadAt(roomId, userId, ...)` and write NO `chatflow:readat:*` key (verify no `opsForValue().set` with a readat key); positions-hash behavior unchanged; #18's circuit/fail-soft semantics preserved (cursor touch inside the same guarded try — a DB failure must not kill the STOMP frame: wrap in the existing try/catch structure; note the cursor write is DB not Redis, so it sits OUTSIDE the Redis circuit check but inside its own try/catch → log + continue-without-broadcast stays governed by the Redis outcome as today... simplest correct shape: do the DB cursor touch FIRST in its own try/catch (fail-soft, log warn), then the existing Redis block unchanged).
- [ ] **Step 2: Implement** `ReadReceiptService`: replace both `chatflow:readat` writes with `roomMemberRepository.touchLastReadAt(roomId, userId, LocalDateTime.now())` (fail-soft try/catch, warn log). Inject `RoomMemberRepository`. `UnreadCountService`: delete the multiGet/Group A/Group B/epoch logic; single `countUnreadByCursor` call + 0L-initialized LinkedHashMap overlay; drop `StringRedisTemplate` + `RedisHealthTracker` deps; fail-soft try/catch → all-zeros map on DB error (endpoint must not 500).
- [ ] **Step 3:** grep `chatflow:readat` → zero hits; grep `countNewChatMessages` — if the singular/batch variants have no remaining callers, DELETE both from `ChatMessageRepository` (and their tests); if something else calls them, leave + note.
- [ ] **Step 4:** Full `./gradlew :chat-service:test` green.
- [ ] **Step 5: Commit** `refactor(chat-service): unread counts read the durable cursor in one query; readat keys retired`.

## Task U3: Review + merge
- [ ] Whole-branch review (behavior changes 1-4 verified as designed; no other readat/HGETALL seam broken; frontend contract of GET unread-counts unchanged), full backend suite, merge to develop, push.

## Self-review notes
- The positions HASH (`chatflow:read:{roomId}`) is deliberately untouched — it serves per-message receipt display, not unread counting.
- `updateLastMessageAt` and message-send paths are untouched; only the read side changes.
- H2 portability: JPQL theta join + COALESCE are spec-standard; validated at context load by existing @DataJpaTest configs.
- The V12 backfill UPDATE is idempotent (`WHERE last_read_at IS NULL`).
