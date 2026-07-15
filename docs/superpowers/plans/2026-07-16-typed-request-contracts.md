# Typed & Authenticated Request Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Replace chat-service's untyped `@RequestBody Map<...>` bodies (11 endpoints across 6 controllers) and the one `@RequestBody ChatRoom` JPA-entity binding with `@Valid` request records, and migrate the 5 controllers that read raw `@RequestHeader("X-User-Id")` to the `@AuthenticatedUser`/`@RequireAuth`/`@RequireMember` infrastructure. This gives typed request schemas (readable + testable), real Bean Validation (now that `BaseExceptionHandler` maps `MethodArgumentNotValidException` → structured 400 — shipped in the exception-4xx cluster), a mass-assignment fix on createRoom, and one unified 401 contract.

**Architecture:** One request record per endpoint (nested `public record XxxRequest(...)` inside its controller, mirroring the existing `ChatRoomController.GetOrCreateRequest` precedent), `@Valid`-annotated, with `jakarta.validation` constraints (`@NotBlank`, `@Size`). Hand-rolled null/blank guards are DELETED (BaseExceptionHandler's `MethodArgumentNotValidException` handler now returns a structured 400 with field errors). Auth identity comes from `@AuthenticatedUser String userId` (+ `@RequireAuth`/`@RequireMember`) instead of raw headers; hand-rolled `X-Username`-null checks are deleted.

**Tech Stack:** Spring Boot 3.2 / Java 17 / Jakarta Bean Validation / JUnit5 + MockMvc/Mockito.

**Constraints / decisions:**
- **Response shapes unchanged** — only the request side changes. Frontend request payloads already send these keys as JSON objects, so a record with matching component names deserializes identically. VERIFY each record's JSON field names match what the Flutter client sends (grep `frontend/lib` for the endpoint's Dio call before finalizing a record).
- `@AuthenticatedUser(required = false)` stays where an endpoint is intentionally anonymous-friendly (e.g. `verifyPassword` seeds membership only if authed).
- `X-Username` is still needed by some endpoints (username for seeding/broadcast) — keep reading it via `@RequestHeader(value="X-Username", required=false)`; the migration is about `X-User-Id` → `@AuthenticatedUser` and deleting the *hand-rolled X-Username null-guards that returned ad-hoc 400s*, not removing the header.
- Records live in the controller as nested types unless shared across controllers (none are). Keep `common` out of it — these are chat-service request DTOs.
- Do NOT change STOMP `@MessageMapping` payloads (those are ChatMessage DTOs already) — REST only.

---

## Task RT1: ChatRoomController — entity + 4 Map bodies → records

**Endpoints:** `createRoom` (`@Valid @RequestBody ChatRoom` → `CreateRoomRequest`), `verifyPassword` (Map → `VerifyPasswordRequest`), `createDm` (Map → `CreateDmRequest`), `updateRoomSettings` (Map → `UpdateSettingsRequest`), REST `sendMessage` fallback (Map → `SendMessageRequest`).

- [ ] Records (nested in ChatRoomController; adjust names/fields to the actual service calls + frontend payload):
  - `CreateRoomRequest(@NotBlank @Size(max=100) String name, @Size(max=500) String description, String color, String roomType, Boolean isPrivate, String password, Boolean allowInvites)` — map to the domain in the controller/service; NEVER accept id/externalId/participantCount/createdAt (mass-assignment fix). Keep the `@JsonProperty("isPrivate")` wire key if the frontend sends `isPrivate`.
  - `VerifyPasswordRequest(String password)` (password may be blank → service handles).
  - `CreateDmRequest(@NotBlank String targetUserId, @NotBlank String targetUsername)`.
  - `UpdateSettingsRequest(String name, String description)`.
  - `SendMessageRequest(@NotBlank String content, String priority, String parentMessageId, String forwardedFrom, String fileUrl, String fileName, String fileContentType)` — cover every `body.get(...)` key the current code reads.
- [ ] Replace each `@RequestBody Map` with `@Valid @RequestBody XxxRequest`; delete the hand-rolled null/blank guards that duplicate the constraints; keep any semantic checks the validation can't express.
- [ ] createRoom: build the domain `ChatRoom`/service call from the record fields explicitly (whitelist), fixing the missing-name-500 and mass-assignment.
- [ ] Tests: MockMvc — createRoom without `name` → 400 VALIDATION_ERROR (was 500); createDm missing target → 400; each happy path still works (delegates to the same service call). Update existing ChatRoomController tests for the new signatures.
- [ ] `./gradlew :chat-service:test` green. Commit `refactor(chat-service): typed request records for ChatRoomController (+createRoom mass-assignment fix)`.

## Task RT2: MessageInteractionController + RoomInviteController + RoomReadStateController → records

- [ ] MessageInteractionController: `editMessage` Map → `EditMessageRequest(@NotBlank @Size(max=10000) String content)` (keep the existing length-cap semantics); `toggleReaction` Map → `ReactionRequest(@NotBlank String emoji)`; `pinMessage` Map → `PinRequest(@NotBlank String messageId)`.
- [ ] RoomInviteController: the 2 Map bodies (read the endpoints — likely invite-by-username + join-by-code) → records with `@NotBlank` on the required field.
- [ ] RoomReadStateController: the 1 Map body (markLastRead — `roomId`/`lastReadMessageId`) → record.
- [ ] Delete hand-rolled guards superseded by constraints; preserve semantic checks. Tests + `:chat-service:test` green. Commit `refactor(chat-service): typed request records for message/invite/read-state controllers`.

## Task RT3: ScheduledMessageController Map<String,Object> → record (ClassCastException fix)

- [ ] `schedule` currently binds `Map<String,Object>` with unchecked `(String)` casts — a non-string `chatRoomId` → ClassCastException → 500. Convert to a typed record `ScheduleRequest(@NotBlank String chatRoomId, @NotBlank String content, String scheduledAt, ...)` (cover every key read). `scheduledAt` stays a `String` parsed by the existing `DateTimeParseException`-handled path (do NOT change the parse contract). 
- [ ] Tests: a non-string / missing field now → 400 (not 500). `:chat-service:test` green. Commit `refactor(chat-service): typed ScheduleRequest — no more ClassCastException on bad chatRoomId`.

## Task RT4: Auth-annotation unification — 5 controllers

**Controllers:** RoomMemberManagementController, RoomBanController, MessageReportController, ScheduledMessageController, MentionDigestController — all read `@RequestHeader("X-User-Id")` directly today.

- [ ] For each handler: replace `@RequestHeader("X-User-Id") String userId` with `@AuthenticatedUser String userId`, add `@RequireAuth` (or `@RequireMember` where a `{roomId}` path var exists AND the endpoint should require membership — check each: ban/report/member-management operate on a room the caller must be a member/mod of; they currently call `roomPermissionService.requireRole(...)` per-handler — KEEP those role checks, just switch the identity source).
- [ ] Delete the hand-rolled `X-Username`-null `IllegalArgumentException`/400 guards (MentionDigestController repeats one 3×) where the identity now comes from the resolver; keep `X-Username` header reads where the username value is genuinely used.
- [ ] Verify a missing identity now yields the resolver's 401 (via AuthInterceptor), not the old catch-all 500. Tests: update each controller's tests for the new signatures; add one asserting a missing-auth request → 401 for a representative endpoint. `:chat-service:test` green. Commit `refactor(chat-service): unify auth via @AuthenticatedUser across ban/report/member/scheduled/mention controllers`.

## Task RT5: whole-branch review + merge
- [ ] Full `./gradlew test`; frontend request-contract spot check (records' JSON keys == Dio payloads); whole-branch review (no response-shape drift, auth semantics preserved, validation 400s correct); merge to develop + push.

## Self-review notes
- Each record's component names ARE the JSON keys (Jackson) — must equal the frontend's sent keys; verify per endpoint.
- BaseExceptionHandler (common) now returns `VALIDATION_ERROR` 400 with `fieldErrors` for `@Valid` failures — the frontend already handles 400s generically (Dio interceptor keys on status), so this is safe.
- Out of scope: gateway AuthController (WebFlux, separate); STOMP payloads; response DTOs (already mapped).
