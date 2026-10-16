# 2026-10 Audit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the security holes and correctness bugs found by the 2026-10 full audit, and delete verified dead/legacy code, without changing intended product behaviour except where listed.

**Architecture:** Four waves. Tasks inside one wave touch disjoint files and run in parallel git worktrees branched from `develop`; each wave is merged to `develop` before the next wave starts. Every bug fix starts with a failing regression test (TDD). No deployment in this plan.

**Tech Stack:** Spring Boot 3.2 / Java 17 (Gradle multi-module), Spring Cloud Gateway (WebFlux), Spring Kafka, Elasticsearch Java client, Flutter 3.x / Riverpod 2 / stomp_dart_client 2.1, Helm.

**Spec:** Audit report https://claude.ai/artifact/CTFLrAM5f5gpPhaZQF3wRB — finding IDs (S-*, B-*, F-*, I-*) below refer to it. Each task lists the finding IDs it closes; the report has file:line, failure scenario and fix direction for each.

## Global Constraints

- Branch from `develop`; branch name `fix/audit-<task-number>-<slug>`; one worktree per task under `.worktrees/`.
- Conventional Commits with the trailers from the user's global CLAUDE.md (`Constraint:`, `Rejected:`, `Confidence:`, `Scope-risk:`, `Not-tested:` where applicable), ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Never write real secret values into any tracked file (placeholders only).
- Backend verify: `./gradlew :<module>:test` must pass for every touched module. Frontend verify: `cd frontend && flutter analyze` (no new warnings) and `flutter test`.
- Kafka consumers keep `StringDeserializer` (listener factory deserializes the payload itself).
- Do NOT change: AI provider wiring (LangChain4J in prod, Spring AI local — decision deferred), major dependency versions, frontend HANDOFF/PatientCard UI and the `PATIENT_CARD` message type (product decision: keep).
- Product decisions already made by the user: DM rooms are visible only to their two participants; `isPrivate` rooms are visible only to members (enter via invite link); password rooms stay listed but require password verification before joining; FHIR backend mock is deleted.
- Flyway: next free migration number is **V16** (check `chat-service/src/main/resources/db/migration` first; if a task in the same wave also needs a migration, the lower task number takes V16 and the other V17).

---

## Wave 1 — Security (parallel: T1–T5)

### Task 1: Single room access policy and join gate

Closes: S-1, S-2, S-7, S-8, S-11 (invite/participants/report membership parts), dead D: `get-or-create`, creator legacy bridge.

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/service/room/RoomAccessPolicy.java`
- Modify: `service/room/RoomMembershipChecker.java` (remove `isCreator`, `MembershipResult.CREATOR_ONLY`), `controller/RoomMembershipGuard.java` (remove creator backfill), `service/room/RoomMembershipService.java` (`addMemberIfAbsent` → goes through policy), `service/presence/ParticipantRegistryService.java:43-57` (no implicit row insert unless policy allows auto-join), `service/UserPresenceService.java`, `controller/ChatRoomController.java` (room list filtering, `/verify`, `/participants`, `createDm`, delete `/get-or-create`), `controller/RoomInviteController.java` (`inviteUser` → `@RequireMember`, `@Size(max=50)` on `targetUsername`; `joinByInvite` → policy), `service/room/ChatRoomService.java` (remove `getOrCreateByExternalId`, `verifyPassword` returns false for null password), `repository/ChatRoomRepository.java` (remove `findByExternalId`, add visible-rooms query), `service/moderation/MessageReportService.java` (`submitReport` requires membership), `service/message/MessageSenderService.java` (drop `creatorOnly` handling).
- Test: `chat-service/src/test/java/com/chatflow/chat/service/room/RoomAccessPolicyTest.java` (new), update existing tests that reference `CREATOR_ONLY` / creator bridge / get-or-create.

**Interfaces:**
- Produces:
```java
@Component @RequiredArgsConstructor
public class RoomAccessPolicy {
    /** Why a join was refused; JoinDecision.allowed() == (reason == null). */
    public enum Denial { ROOM_NOT_FOUND, BANNED, DM_NOT_PARTICIPANT, PRIVATE_REQUIRES_INVITE, PASSWORD_REQUIRED, ROOM_FULL }
    public record JoinDecision(Denial reason) { public boolean allowed() { return reason == null; } }

    /** Implicit join (STOMP addUser, first visit): only public, password-less, non-DM rooms. Banned → BANNED. */
    public JoinDecision canAutoJoin(String roomId, String userId);
    /** Explicit grant via a verified path. via = PASSWORD | INVITE | DM_CREATE. Always checks ban. */
    public JoinDecision canGrant(String roomId, String userId, GrantPath via);
    /** Visible in room list: GENERAL non-private rooms, plus any room the user is a member of. */
    public boolean isVisible(ChatRoom room, String userId);
    public enum GrantPath { PASSWORD, INVITE, DM_CREATE }
}
```
  `RoomMembershipService.grantMembership(String roomId, String userId, String username, GrantPath via)` returns `JoinDecision` and is the ONLY method allowed to insert `room_members` rows from user-initiated flows (`ParticipantRegistryService.register` calls `canAutoJoin` first and inserts only on allowed). Owner seeding on room creation keeps its existing direct call.
- DM participant check: a user is a DM participant iff a `room_members` row exists (DMs are seeded with both members at creation by `DmRoomService`). Ban check uses `RoomBanService.isBanned(roomId, userId)`.

- [ ] **Step 1: Write failing tests** in `RoomAccessPolicyTest` (Mockito, same style as `RoomBanServiceTest`):
  - `autoJoin_publicRoom_allowed`
  - `autoJoin_dmRoom_nonParticipant_deniedDmNotParticipant`
  - `autoJoin_passwordRoom_deniedPasswordRequired`
  - `autoJoin_privateRoom_deniedPrivateRequiresInvite`
  - `autoJoin_banned_deniedBanned`
  - `grant_invite_banned_deniedBanned` (S-7)
  - `grant_password_onRoomWithoutPassword_denied` (S-2)
  - `isVisible_dmOfOthers_false`, `isVisible_privateNonMember_false`, `isVisible_passwordRoom_true`
  - In `ChatRoomServiceTest`: `verifyPassword_roomWithNullPassword_returnsFalse`.
  - In `RoomMembershipGuardTest` (or create): `requireMember_formerCreatorWithoutRow_forbidden_noBackfill` (S-8).
  - In `ParticipantRegistryServiceTest`: `register_dmRoomNonParticipant_doesNotInsertMemberRow` (S-1).
- [ ] **Step 2:** Run `./gradlew :chat-service:test --tests '*RoomAccessPolicyTest' --tests '*ParticipantRegistryServiceTest' --tests '*ChatRoomServiceTest' --tests '*RoomMembershipGuardTest'` → expect FAIL (class missing / wrong behaviour).
- [ ] **Step 3: Implement** the policy and rewire every join path listed in Files. `GET /api/chat/rooms` filters with `isVisible` (pass `X-User-Id`); `GET /rooms/{id}/participants` requires membership; `/verify` returns 403 for DMs, false for null password, and calls `grantMembership(..., PASSWORD)` on success. STOMP `addUser` refused → send the existing error frame to `/user/queue/errors` and do not register presence.
- [ ] **Step 4:** Before deleting the creator bridge, add Flyway `V16__backfill_missing_room_owners.sql` that inserts an OWNER row for any room whose `created_by` has no `room_members` row AND the room has no OWNER at all (idempotent `INSERT ... SELECT ... WHERE NOT EXISTS`), so removing the bridge cannot orphan a room. Do not resurrect owners for rooms that already have an OWNER.
- [ ] **Step 5:** Run `./gradlew :chat-service:test` → all PASS. Grep: `grep -rn "CREATOR_ONLY\|getOrCreateByExternalId\|get-or-create" chat-service frontend/lib` → no hits.
- [ ] **Step 6: Commit** `fix(chat): single room access policy — close join/verify/ban/creator holes`.

### Task 2: STOMP inbound hardening

Closes: S-3, S-6, S-11 (cross-room reply leak), server half of S-14.

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/dto/InboundChatCommand.java`
- Modify: `controller/ChatController.java` (`sendMessage` only — do NOT touch `addUser`; Task 1 owns that path via services), `config/WebSocketConfig.java:64-77` (verify `X-Gateway-Secret` in `beforeHandshake`; reject handshake without it), `config/StompAuthChannelInterceptor.java` (CONNECT: if session has no userId, accept `Authorization: Bearer <jwt>` native header and validate it with the same JWT secret/parsing chat-service already uses for REST — find it in `auth/`; reject CONNECT when no identity can be established), `service/message/MessageSenderService.java:85,105-117` (type whitelist, mute gate for all user types, parent must be in same room), `mapper/ChatMessageMapper.java:46` (`isAiGenerated` never from client).
- Test: `ChatControllerTest`, `StompAuthChannelInterceptorTest`, `MessageSenderServiceTest`, new `WebSocketConfigHandshakeTest` if no existing handshake test.

**Interfaces:**
- Produces:
```java
/** What a client may send on /app/chat.sendMessage. Identity comes from the session, never from here. */
public record InboundChatCommand(
    String chatRoomId, String content, MessageType type,   // type ∈ {CHAT, FILE, PATIENT_CARD}; null → CHAT
    String parentMessageId, String fileUrl, String fileName, Long fileSize, String fileType,
    String clientMessageId,                                  // optional, echoed back on the broadcast (used by Task 12)
    String forwardedFrom) {}
```
  `ChatMessage` broadcast gains nullable `clientMessageId` (add field to `common/.../ChatMessage.java` if absent; Jackson ignores unknown on the frontend side). Before writing the record, read the current `ChatMessage` fields the frontend sends (`frontend/lib/features/chat/internal/message_send_helper.dart`) and include every field the client legitimately sets — the list above is the minimum; do not drop a field the UI relies on.
- CONNECT header contract for Task 10: native STOMP header `Authorization: Bearer <jwt>`.

- [ ] **Step 1: Failing tests:**
  - `sendMessage_sessionWithoutUserId_rejectedNotPersisted` (S-3)
  - `sendMessage_payloadUserIdIgnored_sessionIdentityUsed`
  - `sendMessage_typeSystem_rejected`, `sendMessage_aiGeneratedFlag_forcedFalse` (S-6)
  - `send_mutedUser_patientCard_blocked` (S-6 mute bypass)
  - `send_replyToMessageInOtherRoom_parentIgnored_noPreview` (S-11)
  - `connect_withValidBearerHeader_setsPrincipal`, `connect_withoutAnyIdentity_rejected`
  - `handshake_missingGatewaySecret_rejected`
- [ ] **Step 2:** Run the four test classes → FAIL.
- [ ] **Step 3:** Implement. Rejections send to `/user/queue/errors` where a principal exists, otherwise just drop + `log.warn` with no payload content.
- [ ] **Step 4:** `./gradlew :chat-service:test :common:test` → PASS.
- [ ] **Step 5: Commit** `fix(chat): STOMP inbound command DTO, session-only identity, CONNECT JWT auth`.

### Task 3: Delete FHIR mock / order-events pipeline

Closes: S-5, dead FHIR stack.

**Files:**
- Delete: `chat-service/src/main/java/com/chatflow/chat/fhir/**`, `chat-service/.../service/OrderEventConsumer.java`, its test, `common/src/main/java/com/chatflow/common/**/OrderEvent.java` (verify path with grep), any `order-events` topic constant/bean in `KafkaTopicConfig`.
- Modify: `gateway-service/src/main/resources/application-prod.yml` (remove `fhir-service` route + its circuit breaker instance), `application-local.yml.example` if it has the route, `gateway-service/.../FallbackController.java` (remove `fhirFallback`) + its test, `README.md` (remove FHIR line). Do NOT touch gateway `SecurityConfig.java` (Task 5 owns it and removes the `/api/fhir/**` rule).
- Keep: frontend PatientCard / HANDOFF / `PATIENT_CARD` enum.

- [ ] **Step 1:** `grep -rn "fhir\|Fhir\|FHIR\|OrderEvent\|order-events" --include=*.java --include=*.yml --include=*.yaml --include=*.md . | grep -v '^./frontend/build'` and record the hit list in the commit body.
- [ ] **Step 2:** Delete/modify per Files. The regression guard is a test in `FallbackControllerTest` or a gateway route test asserting no route id `fhir-service` exists (`RouteLocator` bean, `StepVerifier`), if the gateway test setup supports it; otherwise rely on the grep in Step 3.
- [ ] **Step 3:** Re-run the grep → only frontend PatientCard hits (no `/api/fhir`). `./gradlew :chat-service:test :gateway-service:test :common:test` → PASS.
- [ ] **Step 4: Commit** `chore: remove FHIR mock and order-events pipeline (prod SYSTEM-message injection)`.

### Task 4: Link-preview SSRF, file access, mention digest

Closes: S-10, S-11 (file download room check + content type, mention digest after kick).

**Files:**
- Modify: `chat-service/.../service/LinkPreviewService.java`, `config/RestClientConfig.java` (remove unused `restClient()` bean; replace link-preview client), `controller/FileController.java:55`, `service/FileStorageService.java:117` (persist Tika-detected content type at upload; serve it; `image/*` inline only if detected type is image, else attachment), `service/notification/MentionDigestService.java:51-73` (filter to rooms where the user is still a member).
- If file metadata has no table to store the detected type: store it next to the file (e.g. `<uuid>.meta` sidecar) — check `FileStorageService` first and follow what it already does for original filename.
- File room check: only if file metadata already records a room id; if it doesn't, do NOT invent a schema change — instead note in the commit `Not-tested:`/body that file→room binding needs a migration (follow-up), and only fix content type.
- Test: `LinkPreviewServiceTest`, `FileStorageServiceTest`, `MentionDigestServiceTest`.

- [ ] **Step 1: Failing tests:** `preview_redirectToPrivateIp_blocked` (mock server returning 302 to `http://10.0.0.1/` — use `okhttp3.mockwebserver` if present in test deps, else JDK `com.sun.net.httpserver.HttpServer`), `isBlocked_cgnat_100_64`, `isBlocked_ipv6Ula_fc00`, `download_contentTypeFromDetectedType_notExtension`, `digest_excludesRoomsUserNoLongerMemberOf`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement with JDK `java.net.http.HttpClient` (`followRedirects(NEVER)`), manual redirect loop (max 3 hops) re-validating every hop's resolved addresses, connect/read timeouts as currently configured, keep the response size cap that exists today. Precompile `extractOg` regexes as `static final Pattern`.
- [ ] **Step 4:** `./gradlew :chat-service:test` → PASS.
- [ ] **Step 5: Commit** `fix(chat): SSRF-safe link preview, detected content type on download, digest membership filter`.

### Task 5: Gateway hardening

Closes: S-12, S-13, I-6, I-8, gateway half of I-1, dead `ipKeyResolver` / `/api/chat/auth/**` / `/api/fhir/**` rules.

**Files:**
- Create: `gateway-service/src/main/java/com/chatflow/gateway/config/ClientIpResolver.java`
- Modify: `config/LoginRateLimitFilter.java:83-87`, `config/RateLimiterConfig.java` (use resolver; delete `ipKeyResolver`), `security/AuthService.java:84` (ignore `request.role()`, always default role), `security/JwtAuthenticationWebFilter.java` (remove `@Component`; construct in `SecurityConfig`), `security/SecurityConfig.java` (remove `/api/chat/auth/**` and `/api/fhir/**` rules; permitAll `/actuator/health/liveness`, `/actuator/health/readiness`), `config/CorsConfig.java` + `application-prod.yml` globalcors (single source; prod origins `https://app.chatflow.ai.kr` only), `application-prod.yml` + other services' `application-prod.yml` set `management.endpoint.health.probes.enabled: true`.
- Test: `ClientIpResolverTest` (new), `LoginRateLimitFilterTest`, `AuthServiceTest`.

**Interfaces:**
```java
public final class ClientIpResolver {
    /** Order: CF-Connecting-IP → X-Real-IP → remoteAddress. X-Forwarded-For is never trusted. */
    public static String resolve(ServerHttpRequest request);
}
```

- [ ] **Step 1: Failing tests:** `resolve_prefersCfConnectingIp`, `resolve_ignoresSpoofedXForwardedFor`, `loginLimit_rotatingXff_stillLimited`, `register_clientSuppliedAdminRole_ignored`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Verify the JWT filter runs once: add a test or a log-count assertion if the existing test setup allows; otherwise document in commit `Not-tested:`.
- [ ] **Step 4:** `./gradlew :gateway-service:test` → PASS.
- [ ] **Step 5: Commit** `fix(gateway): trusted client IP, server-side role, single JWT filter, tight CORS, probe endpoints`.

---

## Wave 2 — Backend correctness (parallel: T6–T9; starts after Wave 1 merged)

### Task 6: Room authorization for search and AI summary

Closes: S-4.

**Files:**
- Create in chat-service: internal endpoint `GET /internal/rooms/members/{userId}/room-ids` → `List<String>` and `GET /internal/rooms/{roomId}/members/{userId}` → 204/404, guarded by `X-Gateway-Secret`-style shared internal secret (reuse the existing gateway internal secret env var; do not invent a new secret name — find it in `JwtAuthFilter`). Not routed by the gateway (verify no gateway route matches `/internal/**`).
- Create `search-service/.../client/RoomMembershipClient.java` and `ai-summary-service/.../client/RoomMembershipClient.java` (RestClient, base URL property `chatflow.chat-service.url`, default `http://chat-service:8080`; add to helm configmaps and `application-local.yml.example`).
- Modify: `search-service/.../controller/SearchController.java` (read `X-User-Id`; 401 if absent; room-scoped endpoints → membership check 403; global search → restrict query to the user's room ids via a `terms` filter on `chatRoomId`), `ai-summary-service/.../controller/AiSummaryController.java` (all room-scoped routes → membership check).
- Test: `SearchControllerTest`, `AiSummaryControllerTest`, chat-service `InternalMembershipControllerTest`.

- [ ] **Step 1: Failing tests:** `globalSearch_onlyMemberRooms`, `roomSearch_nonMember_403`, `summary_nonMember_403`, `ask_nonMember_403`, `internal_missingSecret_403`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (membership client timeout 2s; on chat-service failure → 503, never fail-open). **Step 4:** `./gradlew :chat-service:test :search-service:test :ai-summary-service:test` → PASS. **Step 5: Commit** `fix(search,ai): enforce room membership on search and AI endpoints`.

### Task 7: Search indexing durability and cleanup

Closes: B-2, B-6, B-9 (search + audit index), B-12, B-14, dead blog/RSS stack, AS-R3 IndexInitializer duplication, AS-R4 highlights (drop the unused highlight request).

**Files:** `search-service/.../service/SearchService.java`, `service/AuditIndexService.java`, `config/ElasticsearchConfig.java` (apply username/password/SSL/timeouts; split comma URIs; reuse `FlexibleLocalDateTimeDeserializer`), `config/IndexInitializer.java` (single `createIfMissing(name, resource)`; drop `blog_posts`), `document/ChatMessageDocument.java` (`@JsonProperty("isAiGenerated")`, add `forwardedFrom`, `edited` if mapping supports — update `korean-analyzer-config.json` mapping accordingly), delete `BlogSearchController`, `RssFeedCrawler`, blog document, `blog-posts-config.json`, `rome` dependency in `search-service/build.gradle`, `KoreanSearchService.java:219-237` highlight request, rename `SearchControllerLegacyDelegationTest` to what it tests.
- Indexing design: batch `@KafkaListener` (`batch = "true"` on a dedicated batch container factory) that bulk-writes index AND delete operations in arrival order, acks only after bulk success; on failure throw so `DefaultErrorHandler` retries and dead-letters (shared `KafkaErrorConfig`). Remove the in-memory buffer/scheduler entirely. Null `type` handled.
- Tests: `SearchServiceTest` — `batch_bulkFailure_propagatesForRetry`, `batch_deleteAfterIndexInSameBatch_documentNotResurrected`, `batch_nullType_noNpe`; `AuditIndexServiceTest` — `esFailure_propagates`; `ElasticsearchConfigTest` — `credentialsApplied`.
- [ ] Steps: failing tests → run FAIL → implement → `./gradlew :search-service:test :common:test` PASS → commit `fix(search): ack-after-bulk indexing, ordered deletes, ES auth; drop blog/RSS`.

### Task 8: AI summary correctness

Closes: B-1, B-9 (ai-summary), B-10, B-11, I-3, dead `ai-summary-requests` topic.

**Files:** `ai-summary-service/.../service/QuickReplyService.java`, `service/AiSummaryService.java`, a new `RedisKeys` constants class in ai-summary-service used by both services' reads/writes, `QuickReplyServiceTest.java:61` (use the shared constant), `chat-service/.../service/message/MessageSenderService.java:153` (remove second outbox row for `ai-summary-requests`), `KafkaTopicConfig` (remove topic), `common` topic constant, `helm/chatflow/values.yaml:24` and `values-dev.yaml:6` (`springProfile: prod,langchain4j` to match `values-k3s.yaml`).
- Design: buffer dedup by messageId (keep a Redis `SET chatflow:buffer:ids:{roomId}` with same TTL; `SADD` returns 0 → skip push); summary cache via `RPUSH` + `LTRIM -N -1` (N = current max kept; if no max exists use 50) + `EXPIRE`; rate-limited/failed summary re-registers the time trigger and returns a failure to `requestSummary` callers (controller returns 429 or 503 with the existing error envelope); JSON parse failures rethrow.
- Tests: `quickReplies_readsSameBufferKeyAsSummaryWriter` (B-1, uses both services' constants), `buffer_duplicateMessageId_notCountedTwice`, `cacheSummary_concurrentAppends_bothKept` (verify RPUSH used), `requestSummary_rateLimited_returnsFalse_andTimerReRegistered`, `listener_badJson_throws`.
- [ ] Steps: failing tests → FAIL → implement → `./gradlew :ai-summary-service:test :chat-service:test :common:test` PASS → `helm template helm/chatflow -f helm/chatflow/values.yaml >/dev/null` succeeds (if helm installed) → commit `fix(ai-summary): quick-reply key, idempotent buffer, atomic cache, honest rate-limit result`.

### Task 9: chat-service delivery correctness

Closes: B-3 (server), B-4, B-5, B-7, B-8, B-13, S-9, mention push dead path (CR-D4).

**Files:** `controller/ScheduledMessageController.java:36` (accept ISO-8601 with offset → `Instant`; still accept naive input interpreted as UTC for backward compat), `service/notification/ScheduledMessageService.java` (claim each due row with conditional `UPDATE ... SET status='PROCESSING' WHERE id=? AND status='PENDING'`, deliver in `REQUIRES_NEW` per row via a separate bean, re-check membership + ban + mute at delivery → FAILED with reason), entity/column type for scheduled time (keep column; store UTC), `config/AsyncConfig.java` (dedicated `broadcastExecutor` single-thread-per-room-order: simplest correct option is a single-thread executor with CallerRunsPolicy; keep FCM on its own pool), `event/MessageEventListener.java` (use broadcast executor), `service/message/MessageEditService.java` (delete history on message delete; `/edits` on deleted message → 404), `service/notification/FcmNotificationService.java` + `controller/FcmController.java` (persist user→token mapping; unsubscribe user's tokens from room topic on kick/ban/leave — hook via an application event published from `MemberManagementService`, `RoomBanService`, `RoomMembershipService.leaveRoom`), remove mention FCM topic sends `MessageSenderService.java:172-176`, Flyway migrations (claim_token index on outbox; FAILED outbox retention handled in `OutboxPoller` cleanup; fcm user token table if needed).
- Tests: `deliverDue_oneRowFails_othersStillDelivered`, `deliverDue_rowClaimedByOtherReplica_skipped`, `deliverDue_authorKicked_markedFailed`, `schedule_offsetInput_storedAsUtc`, `broadcast_queueSaturated_callerRuns_notDropped`, `deleteMessage_purgesEditHistory`, `kick_unsubscribesUserTokensFromRoomTopic`.
- [ ] Steps: failing tests → FAIL → implement → `./gradlew :chat-service:test` PASS → commit `fix(chat): per-row scheduled delivery, ordered broadcasts, edit-history purge, FCM unsubscribe on removal`.

---

## Wave 3 — Frontend (parallel: T10–T12; starts after Wave 2 merged)

### Task 10: STOMP lifecycle and room switching

Closes: F-1, F-2, F-3, F-4, F-9, S-14 (client half), F-14 (keyword banner push→go, ThreadPanel/ChatRoomContent mounted guards).

**Files:** `frontend/lib/core/network/stomp_service.dart` (`reconnectDelay: Duration.zero`; handle `onWebSocketDone` like `_onDisconnect`; cancel `_reconnectTimer` in `_onConnect`; send token in `stompConnectHeaders: {'Authorization': 'Bearer $token'}` on all platforms, keep `webSocketConnectHeaders` for native), `core/network/app_stomp_service.dart` (same three fixes; `connect()` replaces callback when already connected), move `onRoomUpdate` handling out of `chat_room_sidebar.dart` into a non-autoDispose provider in `features/chat/state/` that lives with auth, `features/chat/chat_notifier.dart` (`if (!mounted) return;` after every await in `joinRoom`; `ref.watch(authProvider.select((a) => (a.token, a.userId, a.username)))`), `features/chat/screens/chat_page.dart:423` (`key: ValueKey(roomId)` on `ChatRoomContent`), `chat_room_content.dart:66,71,288`, `thread_panel.dart:52`, `message_send_helper.dart` (wrap `send` in try/catch → offline queue).
- Tests (`frontend/test/`): `stomp_service_test.dart` — `webSocketDone_marksDisconnected`, `onConnect_cancelsPendingReconnect`, `connectHeaders_containBearer`; `chat_notifier_test.dart` — `joinRoom_disposedMidway_doesNotConnect` (fake StompService + completer); widget test `chat_room_content_room_switch_test.dart` — switching roomId re-runs init (activeRoomId updated).
- [ ] Steps: failing tests → `flutter test` FAIL → implement → `flutter analyze && flutter test` PASS → commit `fix(frontend): STOMP done-detection, no ghost sockets, per-room content state, CONNECT auth header`.

### Task 11: Account switch, avatars, server time parsing

Closes: F-5, F-11, F-12, B-3 (client), F-14 (401 exemption, search stale response).

**Files:** create `frontend/lib/core/utils/server_time.dart` (`DateTime? parseServerDateTime(Object? raw)` — naive string → append `Z` → `.toLocal()`; offset string → `.toLocal()`; move `ChatMessage._normalizeTimestamp` logic here and use it from `ChatMessage`), use it in `shared/models/room_member.dart`, `mention_item.dart`, `message_report.dart`, `room_ban.dart` if kept (Task 15 decides — leave untouched if Task 15 deletes it; Task 15 runs in Wave 4 so here: update it), `features/chat/scheduled_messages_provider.dart:40` + `schedule_send_sheet.dart:89` (`toUtc().toIso8601String()`), `features/auth/auth_provider.dart` (one `_clearCredentials()` deleting all five keys, used by logout, expiry and `dio_client.dart:50-52`; always write-or-delete `profileImage`; `copyWith` gains `clearProfileImageUrl`), `core/network/dio_client.dart:44` (exempt only `/api/auth/login`, `/api/auth/register`), `features/profile/profile_provider.dart` (autoDispose + rebuild on userId; after `update()` push avatar into `authProvider`), `features/search/search_provider.dart` (rebuild on userId; request counter guard), `features/chat/chat_rooms_provider.dart` + bookmarks/mentions/keyword providers (watch `authProvider.select((a) => a.userId)`), logout disconnects `appStompServiceProvider`, `shared/widgets/user_avatar.dart` (`buildFullUrl`; initial on error), merge `features/chat/widgets/profile_avatar.dart` into `UserAvatar` and update callers.
- Tests: `server_time_test.dart` (naive, `Z`, `+09:00`, null, garbage), `auth_provider_test.dart` — `logout_clearsAllFiveKeys`, `expiry_clearsProfileImage`; `profile_provider_test.dart` — `userChange_refetches`; `search_provider_test.dart` — `slowFirstResponse_doesNotOverwriteSecond`; model tests for mutedUntil naive input.
- [ ] Steps: failing tests → FAIL → implement → `flutter analyze && flutter test` PASS → commit `fix(frontend): reset per-user state on account switch, UTC-correct timestamps, avatar URL`.

### Task 12: Message list correctness

Closes: F-6, F-7, F-8, F-10, F-13, F-14 (ListView keys).

**Files:** `features/chat/chat_notifier.dart` (single `_mergeMessages(List<ChatMessage> incoming)` used by `joinRoom`, `loadMoreHistory`, `_fetchSummaries`, `mergeMessage`; recomputes `readCounts`; separate `_historyCursor` = oldest CHAT history timestamp), `internal/message_send_helper.dart` (attach `clientMessageId` = existing localId; on retry remove queued original; file / patient-card / askAi go through optimistic+queue path), `internal/stomp_message_dispatcher.dart:145-160` (match echo by `clientMessageId` first, including bubbles in `failed` state), `widgets/chat_messages_list.dart:451-668` (`ValueKey(msg.effectiveId)`), `core/services/fcm_service.dart` (`onTokenRefresh` → resubscribe; suppress foreground notification for `activeRoomIdProvider` room; use `RoomKeywordsNotifier` storage key constant).
- Coordinate with Task 10 (same wave) on `chat_notifier.dart`: Task 10 edits only `joinRoom` mounted guards and the auth `select` line; Task 12 extracts merge logic. Task 12 rebases on Task 10 before merge; reviewer checks the guards survived.
- Contract: server echoes `clientMessageId` (Task 2).
- Tests: `chat_notifier_merge_test.dart` — `cursor_ignoresOlderSummaries`, `readCounts_recomputedAfterHistoryMerge`; `message_send_helper_test.dart` — `retryWhileOffline_queuesOnce`, `lateEcho_matchesFailedBubbleByClientId`, `fileSendWhileOffline_queued`.
- [ ] Steps: failing tests → FAIL → implement → `flutter analyze && flutter test` PASS → commit `fix(frontend): single message merge, stable cursor, clientMessageId dedup, offline file sends`.

---

## Wave 4 — Legacy cleanup and infra (parallel: T13–T15; starts after Wave 3 merged)

### Task 13: Infra and CI cleanup

Closes: I-1 (helm side), I-2, I-4, I-5, I-7, dead workflows/values/EC2 files/create-secrets.

**Files:** delete `.github/workflows/release.yaml`, `.github/workflows/deploy-prod.yaml`, `helm/chatflow/values-prod.yaml`, `values-staging.yaml`, `values-dev.yaml` (after Task 8 edited it — delete anyway), parent `templates/ingress.yaml` (only if `values-k3s.yaml` keeps ingress disabled and nothing else references it), `docker-compose.prod.yml`, `scripts/deploy-ec2.sh`, `scripts/build-prod.sh`, `scripts/cleanup-prod.sh`, `.env.prod.example`, `docs/ec2-deploy-guide.txt`, `scripts/create-secrets.sh`; trim README EC2 section; `scripts/deploy-k3s.sh` (require `DEPLOY_GATEWAY_SECRET`; skip creating secrets that already exist; remove `images`/`helm` stages — keep `infra` and `secrets`, update usage text and CLAUDE.md mention); helm subchart `deployment.yaml` probes → `/actuator/health/liveness` and `/readiness` (all 5 backend subcharts); frontend configmap nginx: `no-cache` for `*.js` and `/assets/`, long cache only for `canvaskit/*.wasm`-style hashed or versioned files (match what the baked `frontend/nginx.conf` did for `flutter.js`); replace baked `frontend/nginx.conf` with the ConfigMap's content minus templated upstreams (or a minimal static server) — no 443 block; remove `logging.file.*` from all `application-prod.yml`; `ci.yml`: `permissions: contents: read`, pin `aquasecurity/trivy-action` to a release tag's commit SHA, bump `actions/cache@v4`, `docker/build-push-action@v6`, `azure/setup-helm@v4`, pin Flutter version to the one in `frontend/pubspec.lock` sdk constraints / local `flutter --version`; `deploy-k3s.yml`: pass `inputs.*` via `env:` and validate `^[A-Za-z0-9._-]+$`, pin cloudflared version + sha256.
- Update CLAUDE.md and `.claude` harness references to deleted scripts (grep `deploy-ec2\|create-secrets\|values-prod\|release.yaml\|deploy-prod`). If `.claude/` mirrors are rendered by personal-harness-kit, edit the source and run `harness render` per memory note; otherwise edit directly.
- Verify: `helm template chatflow helm/chatflow -f helm/chatflow/values-k3s.yaml > /tmp/out.yaml` succeeds and probes paths appear (if helm available); `actionlint` if available; `bash -n scripts/deploy-k3s.sh`.
- [ ] Commit `chore(infra): drop dead workflows/values/EC2 files; probe split; nginx cache fix; pin CI`.

### Task 14: Backend dead code

**Files:** `common/build.gradle` (remove `springdoc-openapi-starter-webmvc-ui`), delete `common/.../ReadReceipt.java`, `UserRole.java`, unused `AuditEvent` constants (`MESSAGE_SEARCH`, `ROOM_JOIN`); chat-service: `MutedException` + handler, `ChatErrorCode.ROOM_FULL`, `MessageReportService.MESSAGE_PREVIEW_MAX_LENGTH`, `ParticipantService.incrementParticipantCount` + repo query, `RoomVisibilityService.unhide`, unused overloads in `ChatService` / `UserPresenceService`, ignored `status` param of `GET /rooms/{id}/reports`; remove `spring.json.trusted.packages` from prod yml files; `ai-summary-service/src/test/resources/application-test.yml` → `StringDeserializer`. Re-grep each symbol across repo (incl. `frontend/lib`, tests) immediately before deleting; skip anything Waves 1–3 started using.
- Do NOT do the `common` api→compileOnly restructure (AS-R1) — out of scope.
- Verify: `./gradlew build` (all modules) PASS.
- [ ] Commit `chore(backend): remove verified-unused classes, constants and springdoc`.

### Task 15: Frontend dead code

**Files:** delete `features/chat/widgets/invite_member_modal.dart`, `widgets/bubbles/link_preview_card.dart`, `features/chat/admin/room_bans_provider.dart` + `RoomAdminApi.listBans/unbanUser` + `shared/models/room_ban.dart` and their tests (only if still unused after Wave 3), `test/mime_type_test.dart`, `test/widget_test.dart`; pubspec: remove `firebase_remote_config`, `cupertino_icons` (keep analytics/performance — native auto-collection); unused `UIConstants` / `ChatStrings` members and `StorageKeys.roomNotificationPolicies` duplicate (use one constant); legacy read-receipt fallback `stomp_service.dart:152-160` (only if Task 10 left it); duplicate inline Ctrl+F search in `chat_room_content.dart:42-73` if `InRoomSearchSheet` covers it (verify keybinding opens the sheet; if not, wire Ctrl+F to the sheet); stale comments `web/firebase-messaging-sw.js:2`, `frontend/Dockerfile:2`; fix the 2 analyzer warnings (`chat_input.dart:1110`, `chat_messages_list.dart:503`).
- Verify: `flutter pub get && flutter analyze && flutter test` PASS; `flutter build web --release` succeeds.
- [ ] Commit `chore(frontend): remove unused widgets, providers, deps and placeholder tests`.

---

## Deferred (not in this plan)

AI provider unification; Spring Boot 3.2.0 → 3.2.12/3.4; Riverpod 3 / go_router 18 / FlutterFire 4; Helm library chart; `common` dependency scoping; APK out of git + release keystore; DM keying by userIds (CR-R7); participant Redis registry consolidation (CM-R2); AFTER_COMMIT typed room events (CM-R3); STOMP service core merge (FC-R2); large-file splits (FC-R1); per-user AI rate limiting (AS-R5).
