# ChatFlow Refactoring & Optimization Roadmap

> **For agentic workers:** This is a *roadmap* (a ranked backlog), not a single executable plan. Tier 0 below is fully specified and executable task-by-task with `superpowers:subagent-driven-development`. Tiers 1–5 are scoped workstreams; the larger ones (marked **needs own plan**) require their own `superpowers:brainstorming` → `superpowers:writing-plans` pass before implementation.

**Goal:** Turn a whole-codebase audit (16 scoped reviewers, adversarial verification) into a prioritized, evidence-backed refactoring program — correctness/security first, then hot-path performance, then structural cleanup, tests, and config hygiene.

**Provenance:** Audit run 2026-07-04/05 as a multi-agent workflow. 164 raw findings → 148 after dedup. 34 survived adversarial verification (correctness + impact lenses, 0 refuted) and 4 came back *partial*; the remaining verifier passes were cut off by a session limit, so those findings carry the reviewer's grade but not an independent second opinion. During synthesis, 6 high-severity items were re-verified by hand against source (all confirmed). Verification status is marked per item: **[V]** adversarially verified, **[H]** hand-verified during synthesis, **[R]** reviewer-only (credible, not independently checked).

**Tech Stack:** Spring Boot 3.2 / Java 17 / Gradle multi-module (chat/ai-summary/search/gateway/common); Flutter 3.22 / Dart 3.3 / Riverpod 2.5 / Dio 5 / stomp_dart_client; PostgreSQL 16 + Flyway; Valkey (Redis); Elasticsearch 8.11 + Nori; Kafka 7.4.

**Prime constraint (do not violate):** `common` is a *base* library — services depend on `common`, never the reverse. Wire DTOs live in `common/dto`; entity enums cross the boundary as `String`. Anything hoisted into `common` must keep its dependency surface minimal.

**Already done — do NOT re-propose:** MapStruct mappers for ChatMessage/ChatRoom/ScheduledMessage/Member; OutboxPoller poison-pill retry cap (retry_count + FAILED after 10, migration V9); frontend `ApiResponse` helpers (`frontend/lib/core/network/api_response.dart`, adopted by `chat_rooms_provider`). Ban/Report/Mention mappers were deliberately kept hand-rolled. `Result<T,E>` mass-migration was deliberately rejected.

---

## How this roadmap is prioritized

Rank = **(severity × blast-radius) ÷ effort**, correctness/security weighted above cleanliness. Effort: **S** = <1h, **M** = ~half a day, **L** = multi-day. The tiers are ordered so each is independently shippable and leaves `develop` green:

| Tier | Theme | Items | Rough effort |
|------|-------|-------|--------------|
| **0** | Correctness & security quick wins | 9 | ~1–1.5 days total |
| **1** | Hot-path performance | 6 | ~3 days |
| **2** | Structural refactor & dedup | 9 clusters | needs own plans |
| **3** | Test-coverage buildout | 7 | ~3–4 days |
| **4** | Config & deployment hygiene | 8 | ~1.5 days |
| **5** | Long-tail cleanup (dead code, minor dup) | ~34 lows | opportunistic |

Cross-finding merges are noted inline — several reviewers independently hit the same root cause (e.g. AuthController blacklist appears 3×, ReadReceiptService SCAN 2×, JwtUtil duplication 2×).

---

# TIER 0 — Correctness & Security Quick Wins (execute first)

These are genuine bugs surfaced during the refactor review. All are small, high-value, and independently shippable. **This tier is fully specified below and ready for `subagent-driven-development`.** Each task is TDD where a test can pin the behavior; the ES-config and dead-code items are verify-by-inspection.

**Test infra note:** chat-service uses JUnit5 + AssertJ + Mockito (73 test files, `MockitoExtension` pattern — see `MessageReadServiceTest`). The `test` profile disables Flyway and uses H2 `create-drop`, so new columns/tables auto-apply in tests. Prod migrations are `chat-service/src/main/resources/db/migration/V*.sql`.

---

### Task 0.1: Room owner-only actions must use role, not stale `createdBy` **[V][H]**

**Bug:** `ChatRoomController.deleteRoom` (line 210) and `updateRoomSettings` (line 290) gate on `room.getCreatedBy().equals(userId)`, but `MemberManagementService.transferOwnership` only swaps `RoomRole` rows and never updates `createdBy`. After an ownership transfer the demoted ex-owner can still delete/reconfigure the room and the new OWNER gets 403. Two "who is the owner" models coexist; the rest of the moderation stack already uses role-based checks.

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java:210` and `:290`
- Test: `chat-service/src/test/java/com/chatflow/chat/controller/ChatRoomControllerTest.java` (or a focused slice test)

- [ ] **Step 1** — Write a failing test: seed room created by `userA`, transfer ownership to `userB` (via `MemberManagementService.transferOwnership`), assert `userB` can `deleteRoom`/`updateRoomSettings` (currently 403) and `userA` cannot (currently 200).
- [ ] **Step 2** — Run it, confirm it fails on the current `createdBy` gate.
- [ ] **Step 3** — Replace both `createdBy` comparisons with `roomPermissionService.requireRole(roomId, userId, RoomRole.OWNER)`. `RoomMembershipGuard` already backfills the legacy creator as OWNER, so pre-transfer rooms still authorize; `PermissionDeniedException` maps through `GlobalExceptionHandler`. Inject `RoomPermissionService` if not already present.
- [ ] **Step 4** — Run tests, confirm pass.
- [ ] **Step 5** — Commit: `fix(chat-service): authorize room delete/settings by OWNER role, not stale createdBy`.

---

### Task 0.2: `leaveRoom` must delete the `room_members` row **[V][H]**

**Bug:** `RoomMembershipService.leaveRoom` (line 87) removes the Redis participant session, broadcasts LEAVE, and syncs the count — but never deletes the DB row. The user stays a member: `addMemberIfAbsent` no-ops on re-entry, and every role/`@RequireMember` check still treats them as present after they "left."

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/room/RoomMembershipService.java:87`
- Verify repo method exists: `chat-service/.../repository/RoomMemberRepository.java` (add `deleteByRoomIdAndUserId` if absent — needs `@Modifying @Transactional`)
- Test: `RoomMembershipServiceTest`

- [ ] **Step 1** — Failing test: add member, `leaveRoom`, assert `roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)` is now `false`.
- [ ] **Step 2** — Run, confirm fail (row persists).
- [ ] **Step 3** — Add `roomMemberRepository.deleteByRoomIdAndUserId(roomId, userId)` inside the `@Transactional leaveRoom`, after the existence check. Add the `@Modifying` repo method if missing.
- [ ] **Step 4** — Run tests, confirm pass. Add a re-join test (leave → `addMemberIfAbsent` → member again).
- [ ] **Step 5** — Commit: `fix(chat-service): leaveRoom removes room_members row so membership actually ends`.

---

### Task 0.3: Cross-room authorization on message-scoped operations **[V]**

**Bug:** `MessageReactionService` (line 31) and sibling message services accept a `messageId` and act on it without verifying the message belongs to the room the caller is authorized for. A member of room A can react to / interact with a message in room B by supplying its id.

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/message/MessageReactionService.java` (and audit `MessageEditService`, `MessageThreadService`, `MessageInteractionController` for the same pattern)
- Test: `MessageReactionServiceTest`

- [ ] **Step 1** — Failing test: message in room B, caller authorized only for room A, assert the operation is rejected (FORBIDDEN/NOT_FOUND).
- [ ] **Step 2** — Run, confirm the op currently succeeds.
- [ ] **Step 3** — After loading the entity, assert `entity.getChatRoomId()` matches the authorized room (or run `roomPermissionService.requireRole(entity.getChatRoomId(), userId, MEMBER)`); return FORBIDDEN otherwise. Apply consistently across message services.
- [ ] **Step 4** — Run tests, confirm pass.
- [ ] **Step 5** — Commit: `fix(chat-service): verify message belongs to authorized room before mutating`.

---

### Task 0.4: STOMP SUBSCRIBE room-membership authorization **[V]**

**Bug:** `WebSocketConfig` (line 55) inbound interceptor authenticates the CONNECT but does not authorize SUBSCRIBE frames against room membership. Any authenticated user can `SUBSCRIBE /topic/chat/{anyRoomId}` and eavesdrop on any room's live messages.

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/config/WebSocketConfig.java:55` (the `ChannelInterceptor.preSend`)
- Test: WebSocket interceptor unit test

- [ ] **Step 1** — Failing test: authenticated non-member SUBSCRIBE to `/topic/chat/{roomId}` — assert rejected.
- [ ] **Step 2** — Run, confirm currently allowed.
- [ ] **Step 3** — In `preSend`, when `StompCommand.SUBSCRIBE` and destination matches `/topic/chat/{roomId}` (also `.../thread/...`, `.../errors`), extract `roomId` and call the membership guard (`RoomMembershipGuard`/`RoomPermissionService.requireRole(...MEMBER)`); throw/deny on failure. Leave `/user/queue/**` alone (user-scoped).
- [ ] **Step 4** — Run tests, confirm pass; add a member-allowed positive test.
- [ ] **Step 5** — Commit: `fix(chat-service): authorize STOMP SUBSCRIBE against room membership`.

> Interaction: Task 1.x (STOMP send path) and this share the membership-lookup cost. When both land, cache the membership check (short Valkey TTL) so per-frame authorization is not a DB hit.

---

### Task 0.5: Gateway `/api/auth/profile` & `/password` bypass the JWT blacklist **[R][H]**

**Bug (hand-verified):** `SecurityConfig` marks `/api/auth/**` `permitAll()`, so the `JwtAuthenticationWebFilter` blacklist gate never blocks these routes. `AuthController.updateProfile`/`changePassword` then *re-parse* the Bearer token themselves via `jwtUtil.getUsername(token)` with no blacklist check — a logged-out (blacklisted) token can still change the password/profile. Also merges: *"Gateway AuthController re-parses Bearer tokens on permitAll routes"* (dup) and *"AuthController endpoints untested — malformed token → 500 not 401"* (test-gap).

**Files:**
- Modify: `gateway-service/src/main/java/com/chatflow/gateway/controller/AuthController.java:39-75`
- Modify: `gateway-service/src/main/java/com/chatflow/gateway/security/SecurityConfig.java:36` (move authenticated auth routes out of blanket `permitAll`)
- Test: `AuthControllerTest` (new)

- [ ] **Step 1** — Failing test: blacklisted token → `PUT /api/auth/password` must be 401, not 200.
- [ ] **Step 2** — Run, confirm currently succeeds.
- [ ] **Step 3** — Two options; prefer **(a)**: (a) require auth for `/api/auth/profile` and `/api/auth/password` (keep only `/register`, `/login`, `/logout` as `permitAll`), and read the filter-injected `X-User-Id`/`X-Username` header instead of re-parsing the token — the filter already validated + blacklist-checked it. (b) If they must stay permitAll, inject `TokenBlacklistService` and reject blacklisted `jti` inside the controller. Also wrap `jwtUtil.getUsername` so a malformed token → 401 not 500.
- [ ] **Step 4** — Run tests (valid-token happy path + blacklisted + malformed), confirm pass.
- [ ] **Step 5** — Commit: `fix(gateway): auth profile/password honor JWT blacklist; stop re-parsing tokens`.

---

### Task 0.6: Elasticsearch mapping references undefined analyzer `ngram_analyzer` **[R][H]**

**Bug (hand-verified):** `korean-analyzer-config.json:142` — the `fileName.ngram` sub-field sets `"analyzer": "ngram_analyzer"`, but only `korean_analyzer`, `korean_ngram_analyzer`, `korean_search_analyzer` are defined (every other ngram sub-field correctly uses `korean_ngram_analyzer`). Index creation fails on any fresh cluster with *analyzer [ngram_analyzer] not found*.

**Files:**
- Modify: `search-service/src/main/resources/elasticsearch/korean-analyzer-config.json:142`

- [ ] **Step 1** — Change `"analyzer": "ngram_analyzer"` → `"analyzer": "korean_ngram_analyzer"` (match lines 96 & 110).
- [ ] **Step 2** — Verify with a JSON check that every referenced analyzer value is in the defined set (the audit's parser confirmed only this one reference is orphaned).
- [ ] **Step 3** — If feasible, spin a throwaway ES container and let `IndexInitializer` create `chat_messages` to confirm no mapping error. (Also see Tier 4: `IndexInitializer` swallows this failure silently.)
- [ ] **Step 4** — Commit: `fix(search): use defined korean_ngram_analyzer for fileName.ngram (fresh-cluster index creation)`.

---

### Task 0.7: Message delete/edit must propagate to Elasticsearch **[R][H]**

**Bug (hand-verified):** `MessageEditService.deleteMessage`/`editMessage` update Postgres and STOMP-broadcast, but never publish to Kafka `chat-messages` (the only path search-service indexes from). Deleted/edited messages remain in ES with their **original plaintext** content — searchable forever after a "delete." Privacy + consistency gap.

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/message/MessageEditService.java:44,102`
- Likely modify: search-service consumer to handle `MESSAGE_DELETED`/`MESSAGE_EDITED` (update/delete the ES doc)
- Test: `MessageEditServiceTest` (verify an event is enqueued) + search consumer test

- [ ] **Step 1** — Failing test: after `deleteMessage`, assert an outbox/Kafka event of type delete for that `messageId` is produced.
- [ ] **Step 2** — Run, confirm nothing is published today.
- [ ] **Step 3** — Publish a delete/edit event via the **outbox** (not fire-and-forget — consistent with the existing outbox pattern). Add a search-service consumer branch that deletes the ES doc on delete and re-indexes on edit.
- [ ] **Step 4** — Run tests, confirm pass.
- [ ] **Step 5** — Commit: `fix(chat+search): propagate message delete/edit to Elasticsearch`.

> Depends conceptually on Tier 4's Kafka deserializer fix if you exercise the search consumer locally.

---

### Task 0.8: Per-user join rejections are broadcast room-wide **[R][H]**

**Bug (hand-verified):** `RoomFullnessService` (lines 52 & 69) sends `ROOM_FULL`/`ROOM_FULL_DM` (with `redirectTo`) to `/topic/chat/{roomId}/errors` — a room-wide topic. If the client acts on `redirectTo`, *every* subscribed member is force-navigated, not just the rejected user. Merges with `MessageSenderService:65` (MUTED/NOT_A_MEMBER sent to `/user/queue/errors` that **no frontend subscribes to** — see Task 2.x STOMP consolidation).

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/presence/RoomFullnessService.java:52,69`
- Frontend: ensure subscription to `/user/queue/errors` exists (coordinate with Tier 2 STOMP work)

- [ ] **Step 1** — Failing test: assert the rejection is sent to a **user-scoped** destination (`convertAndSendToUser(userId, "/queue/errors", ...)`), not the room topic.
- [ ] **Step 2** — Run, confirm it currently targets the room topic.
- [ ] **Step 3** — Switch both sends to `messagingTemplate.convertAndSendToUser(...)`. Add the matching frontend subscription (Task 2 consolidates the two error queues into one handler).
- [ ] **Step 4** — Run tests, confirm pass.
- [ ] **Step 5** — Commit: `fix(chat): send room-full/banned rejections to the rejected user only`.

---

### Task 0.9: Dead delivery-status UI + write-only user cache (safe deletions) **[R]**

Two low-risk dead-code removals that reduce noise and prevent confusion:

**9a — Frontend delivery-status/retry unreachable:** `chat_bubble.dart:483` nests the delivery-status indicator + retry button inside the `!isMine` branch, so a sender never sees send-status/retry. Either move it into the `isMine` branch (if the feature is wanted) or delete it (if abandoned). Decide with the product intent; default to **move into `isMine`** since optimistic-send confirmation exists (`StompMessageDispatcher`).

**9b — Gateway write-only user cache:** `AuthService.getUserRecord` is dead code; `chatflow:user:*` keys are written but never read. Delete the cache write + dead getter.

- [ ] **Step 1** — For 9a: grep for the status widget's usages; move-or-delete per intent; if moved, add a widget test that a sending message shows the indicator for the sender.
- [ ] **Step 2** — For 9b: delete `getUserRecord` and the cache-write; confirm no references (`grep chatflow:user:`).
- [ ] **Step 3** — `flutter analyze` + gateway tests clean.
- [ ] **Step 4** — Commit(s): `fix(frontend): surface delivery-status/retry to the message sender` / `chore(gateway): remove write-only user cache dead code`.

---

# TIER 1 — Hot-Path Performance (own mini-plan each; batchable)

High blast-radius because these run per-message or per-frame. Each is M effort; write a short plan per item or batch them under one perf plan.

1. **STOMP send path: 6+ blocking DB round trips per message on the WS inbound thread** *(perf-backend, H)* — `ChatController:44`. Includes a duplicated `room_members` lookup (also flagged as `ChatController.isMember` re-implementing `RoomMembershipGuard`, Task 2 dedup). Cache membership/mute state in Valkey with short TTL; collapse duplicate lookups; move non-critical writes off the inbound thread.
2. **Unread counts: one COUNT/room + cursor TTL cliff** *(cs-services-support, H)* — `UnreadCountService:91`. 24h cursor TTL silently degrades to "count everything since year 2000." Persist the read cursor durably (DB) rather than TTL'd Redis; batch the per-room counts into one grouped query.
3. **ReadReceiptService full-keyspace Valkey SCAN + GET-per-key on every read frame** *(perf-backend H + cs-services-support M — same bug, 2 reviewers)* — `ReadReceiptService:37,74`. Replace keyspace SCAN with a per-room key set / hash; O(1) lookups.
4. **Mention lookup: unbounded leading-wildcard `LIKE`, no membership scope, broken under encryption** *(cs-data H + MentionDigestService M — merge)* — `ChatMessageRepository.findMentionsOf:72`, `MentionDigestService:48`. A `LIKE '%@user%'` over encrypted `content` can never match at-rest ciphertext. Redesign: store mentions as a structured `message_mentions` table populated at send time (indexed by userId), scoped to rooms the user belongs to. Add TTL to the read-set.
5. **Gateway parses/verifies JWT twice per request + BCrypt on the Netty event loop** *(perf-backend, M)* — `JwtAuthenticationWebFilter:43`. Parse once (reuse `Claims`); move BCrypt to a bounded elastic scheduler off the event loop.
6. **Frontend: history prepend into non-reversed `ListView` → scroll jump + repeat-trigger loads** *(fe-widgets-chat, H)* — `chat_messages_list.dart:114`. Use a reversed list or maintain scroll offset on prepend; guard the load-more trigger against re-entrancy.

Secondary perf (roll into the above or Tier 5): `ChatRoomService.updateLastMessageAt` per-message SELECT+UPDATE+double-evict on the send path; ChatPage AppBar whole-state watch rebuilding the page per event *(fe-state)*; per-keystroke typeahead with no debounce *(chat_input.dart)*; `FlutterSecureStorage` read on every Dio request *(low)*.

---

# TIER 2 — Structural Refactor & Dedup (each **needs own plan**)

These are the "refactoring" core. Each cluster is a separate brainstorming+plan unit; several were already flagged out-of-scope in the Stage 4 plan.

1. **JWT/auth consolidation across gateway ↔ chat-service** *(dup, M)* — `JwtUtil` is duplicated verbatim with jjwt version drift; chat-service's `JwtUtil.isValid/getUserId/getUsername` are largely **dead** (gateway validates and injects `X-User-Id`). Decide the trust boundary (gateway-validates vs defense-in-depth), then either hoist a single `JwtUtil` into `common` (minimal deps!) or delete the chat-service copy. Also dedups the `X-Username` URL-decode logic in `JwtAuthFilter`/`WebSocketConfig`.
2. **Request-body typing + auth annotations in chat-service controllers** *(api-boundary, M — 3 merged findings)* — replace untyped `Map<String,String/Object>` bodies (10+ endpoints) and the `@RequestBody ChatRoom` entity with `@Valid` request records (precedent: `FcmController.SubscribeRequest`, `GetOrCreateRequest`); migrate the 5 controllers reading raw `X-User-Id` to `@AuthenticatedUser`/`@RequireAuth`/`@RequireMember`. Deletes hand-rolled guards and unifies the 401 contract; `BaseExceptionHandler` already maps `MethodArgumentNotValidException` → 400.
3. **`GlobalExceptionHandler`/`BaseExceptionHandler` 4xx mapping** *(error-handling, S — but touches all MVC services)* — the `Exception.class` catch-all turns framework 4xx (missing header/param, type mismatch, method-not-supported) into 500s; the `IllegalStateException → 429` mapping is a documented booby-trap. Add explicit framework-exception handlers (don't extend `ResponseEntityExceptionHandler` — collides with existing advice). Add a `@RestControllerAdvice` to ai-summary-service (the only MVC service without one).
4. **Frontend STOMP consolidation** *(fe-core, M — 3 merged: reconnect ownership split, `StompService`/`AppStompService` ~100-line dup, no tests)* — `stomp_service.dart:88`, `app_stomp_service.dart:47`. One reconnect/backoff owner; single token-refresh path; discard the duplicate class; then Tier 3 adds the state-machine tests. Also fixes `/user/queue/kicked|muted` payload `roomId` being discarded (wrong-room attribution).
5. **Outbox dispatch hardening** *(cs-services-support, H+M)* — `OutboxPoller:56,110`. No row-claiming → with 2 replicas every event dispatches **twice**; retries have no backoff and `FAILED` is a terminal black hole that loses events on a short Kafka outage. Add `SELECT … FOR UPDATE SKIP LOCKED` claiming + exponential backoff + a requeue/alert path off FAILED. Move `AuditService` and the `ai-summary-requests` publish onto the outbox (both currently fire-and-forget). Note: `ai-summary-requests` currently has **no consumer** (dead) — decide keep+wire or remove.
6. **KoreanSearchService / SearchService query-builder dedup** *(search, M)* — three search methods duplicate ~70% of query-building/hit-extraction/error-wrapping (incl. a pasted comment); the legacy `Containing` stack runs analyzer-bypassing leading-wildcard queries duplicating `searchWithFilters`. Extract a shared query builder; drop the legacy stack. Fold in the `SearchService` NPE-on-null-type bug (Tier 0-adjacent; also a test gap).
7. **`ChatRoomController` (10 deps / 4 concerns) + `ChatRoomService` decomposition** *(architecture, M — already flagged Stage 4)* — split by concern; extract the `getRoom-or-404` boilerplate repeated 6× across two controllers; stop parsing Redis wire formats inline (duplicates `ParticipantRegistryService`); gate the ungated participants endpoint.
8. **`chat_input.dart` (992 LOC) + ChatBubble/ChatMessagesList dup decomposition** *(fe-widgets-chat, M/L — already flagged Stage 4)* — 510-line build method mixing 7 concerns; ChatBubble duplicates ~90 lines between `isMine`/other; ChatMessagesList re-duplicates the full bubble wiring. Extract cohesive sub-widgets; unify the bubble wiring. Fold in `const` constructors + controller disposal fixes.
9. **Frontend response-envelope + STOMP-merge dedup** *(fe-core/fe-state, M)* — finish the `api_response.dart` adoption long tail (16 inline `data['data']` unwraps across 12 files); extract the merge+dedupe-by-effectiveId+sort ritual duplicated 4× in `chat_notifier`; extract a `DioErrorHandler`; unify web-origin/WS-URL derivation duplicated 5×.

Architecture correctness items to fold into the relevant cluster above: STOMP broadcasts firing inside open transactions *(MessageEditService:102 et al.)*; `ParticipantService` self-invocation bypassing `@Transactional` proxies; migration `V5` hard-depending on gateway's `users` table that no chat migration creates; the **FHIR mock package + order-events mock producer shipping inside production chat-service** (extract to a demo module or gate behind a profile); `common` `build.gradle` leaking heavy unused starters via `api` scope into every service.

---

# TIER 3 — Test-Coverage Buildout

Coverage is lopsided: chat-service 73 test files, but ai-summary 3, search 3, gateway 3, common 1, frontend 28/128. Rank by risk:

1. **Gateway `JwtAuthenticationWebFilter`** *(H)* — header-spoof sanitization + blacklist gate, **zero tests**. This is the security perimeter.
2. **`common/MessageEncryptor` fail-open** *(H)* — returns plaintext on encrypt failure, untested and unmonitored. Test the failure path; add a metric/alert.
3. **search-service Kafka consumer** *(H)* — NPE on null message type kills indexing; buffer/retry drop logic unverified. (Fix the NPE with the test.)
4. **ai-summary `AiSummaryService`** *(H)* — 520-line pipeline, one test (message-type filter only); rate-limit requeue strands messages. Test the requeue + swallow-all-catch paths.
5. **Frontend `StompService` reconnect/backoff state machine** *(H)* — the real-time core, no tests. (Pairs with Tier 2 consolidation.)
6. **`FlexibleLocalDateTimeDeserializer` (common)** *(M)* — only the trailing-Z case works; offset payloads break whole-payload deserialization. Also drives the frontend `mutedUntil` off-by-UTC-offset bug.
7. **`LoginRateLimitFilter` + `StompMessageDispatcher` + `DioClient` 401 interceptor + `KoreanSearchService` query builders** *(M)* — brute-force gate fail-open, optimistic-send match-by-userId+content, auth 401 flow, query construction.

Enabling change: local profile disables Flyway (H2 create-drop) so **migrations are PostgreSQL-only and never exercised before prod, and entity DDL already drifts from migrations** — add a Testcontainers-Postgres migration test so `V*.sql` runs in CI.

---

# TIER 4 — Config & Deployment Hygiene

1. **Kafka value-deserializer diverges local vs prod** *(cs-data + search + `KafkaCommonConfig` — merge)* — local `JsonDeserializer` breaks the String-payload AI-summary/search listeners; the custom `kafkaListenerContainerFactory` in `common` silently discards `spring.kafka.listener.concurrency: 3` (all consumers single-threaded). Align local↔prod; honor concurrency.
2. **`application-prod.yml` uses Spring Boot 2 property names** *(cs-data, M)* — Tomcat thread cap, log rotation, Prometheus flag silently ignored under Boot 3. Rename to current keys.
3. **`KafkaTopicConfig` hardcodes replication factor 3** *(cs-config-auth, S)* — topic declarations fail on single-broker local. Make it `${KAFKA_REPLICATION_FACTOR:1}`.
4. **Three competing CORS configs with divergent origin lists** *(gateway-common, S)* — collapse to one source of truth.
5. **Gateway route drift local vs prod** *(gateway-common, S)* — fhir/fcm routes missing locally; the `.example` template asserts a prod `/ws-native` bug that is provably false; dead tomcat config. Reconcile.
6. **`LangChainConfig`: no timeout/retry on the Gemini client** *(ai-summary, S)* — REST endpoints call Gemini synchronously; a hang ties up request threads and the Tomcat thread cap is a dead property. Set connect/read timeouts + bounded retry.
7. **`@Scheduled` tasks share one default scheduler thread** *(cs-services-support, S)* — the outbox poll can block 30s/future, starving the other 4 scheduled tasks. Configure a `ThreadPoolTaskScheduler` pool.
8. **Operational constants hardcoded across beans** *(low)* — hoist to `@ConfigurationProperties`.

---

# TIER 5 — Long-Tail Cleanup (opportunistic, ~34 lows)

Group and knock out during adjacent work. Highlights:

- **Dead code:** `BlogSearchController` (unreachable via gateway, no frontend); `PatientCardPayload`/`UserRole` unused DTOs in common; `ChatMessageRepository.deleteMessagesOlderThan` + `ChatRoom.isFull()` uncalled; `chat_input.dart` `buildSbarTemplate()`/`_mentionQuery`; ai-summary ships two full AI provider stacks + unused WebFlux starter; highlights computed on every korean/ngram query but never returned; dead `PopScope` branch in SearchPage.
- **Correctness lows:** ES `from+size` past `max_result_window` (10k) → 500s (cap page number); `ScheduledMessage.fromJson` hard-casts id (one bad item throws); router drops `?redirect=` (breaks post-login invite deep-link); command-palette spinner never clears below 2 chars; search highlight uses live field text not executed query; raw room UUIDs shown to users in mentions/scheduled screens.
- **Minor dup:** relative-time/HH:mm formatting across admin/chat; responsive sheet/dialog + confirm-dialog scaffolding across 4+ modals; ThreadPanel reimplements the edit dialog verbatim; login role dropdown duplicates `_NebField` decoration; two `@mention` grammars disagreeing between FCM push and UNREAD_INCREMENT.
- **Perf lows:** `RoomBanController.banUser` re-lists all bans to recover one row; outbox pending gauge SELECTs 50 TEXT-payload rows per scrape; report rate-limit query missing a supporting index; ai-summary `AsyncConfig` `CallerRunsPolicy` re-blocks Kafka listener threads under saturation.
- **Frontend-state lows:** non-autoDispose family/admin providers accumulate one live notifier per visited room (stale data); `roomUnreadCountsProvider` mutated by copy-mutate-write from 3 sites; undisposed `TextEditingController`s in function-scoped dialogs; RichText ignores system font scaling (a11y); search hard-caps at 20 with no pagination while showing a larger total.

---

## Self-review notes

- **Coverage:** every one of the 148 audit findings maps to a tier (0: 9 tasks; 1: 6 + secondaries; 2: 9 clusters absorbing ~30 architecture/dup/api-boundary items; 3: 7 + the Flyway/Testcontainers enabler; 4: 8; 5: the 34 lows + remaining mediums).
- **Merges applied:** AuthController blacklist (3→Task 0.5), ReadReceiptService SCAN (2→Tier 1.3), JwtUtil dup (2→Tier 2.1), mention `LIKE` (2→Tier 1.4), SearchService null-type NPE (3→Tier 2.6/3.3), STOMP dup/reconnect/tests (3→Tier 2.4/3.5), Kafka deserializer (3→Tier 4.1).
- **Not independently re-verified [R]:** most of Tier 1–5 carries the reviewer's grade only (verifier passes were cut by a session limit). Before implementing any [R] item, the executing subagent should re-confirm against source as its first step — cheap, and the plan's TDD "write the failing test first" enforces it naturally.
- **Deferred/unchanged:** deployment (cloudflared token) and workstream A (stale GHCR fleet) remain blocked/out of scope per prior decision.
