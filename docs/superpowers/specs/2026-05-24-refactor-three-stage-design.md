# ChatFlow Three-Stage Refactor — Design

**Status**: spec (pending review)
**Date**: 2026-05-24
**Author**: brainstorming session (user + assistant)
**Topic**: codebase-wide refactor with regression-minimization, design patterns,
duplication removal, encapsulation improvements

---

## 1. Goal

Move the codebase toward smaller, focused units with explicit responsibility
boundaries — without taking on a multi-week stop-the-world rewrite. Each stage
ships independently, each PR ships behind a fresh code review, and the prior
stage's safety net carries the next stage's risk.

The user's five requirements, in priority order:

1. **Regression minimization** — Stage 1 (safety net) must precede behavioral
   refactors.
2. **Design pattern adoption** — concrete patterns only where they fix a
   measured pain (cross-cutting `if userId == null` checks, polymorphic
   failure modes hidden behind `boolean`).
3. **Duplication removal** — focused on the duplications surfaced in the
   diagnostic (gate checks, dialog-and-menu boilerplate, message-bubble
   variants).
4. **Encapsulation** — push state and side-effects to the lowest level
   that owns them (chat_notifier currently mixes message CRUD, read state,
   connection lifecycle, AI suggestions, mention digests).
5. **Ideal code flow** — three vertical slices (frontend widget tree,
   backend controller→service→repo, AOP cross-cutting) end up with one
   responsibility per file.

---

## 2. Diagnostic snapshot (2026-05-24)

### Backend top files

| File | LOC | Touches /90d | Tests |
|------|-----|--------------|-------|
| `ChatRoomController.java` | 347 | 37 | none |
| `UserPresenceService.java` | 254 | 19 | yes |
| `ChatRoomService.java` | 213 | 35 | none |
| `MemberManagementService.java` | 177 | — | yes |
| `MessageInteractionController.java` | 167 | — | yes |

### Frontend top files

| File | LOC | Touches /90d |
|------|-----|--------------|
| `chat_messages_list.dart` | **2854** | 42 |
| `chat_page.dart` | **1911** | **62** |
| `chat_notifier.dart` | 1076 | 16 |
| `chat_input.dart` | 992 | 33 |
| `chat_room_sidebar.dart` | 688 | 40 |

### Test gap (25 untested backend units)

- **Services (19)**: AiSummaryBroadcast, Audit, ChatPersistence, ChatRoom,
  DmRoom, InviteLink, LinkPreview, MessageEditHistoryRetention, MessagePin,
  MessageReaction, MessageRetention, OrderEventConsumer, OutboxPoller,
  Participant, ReadReceipt, RoomCacheEvictor, RoomMembership, UnreadCount,
  plus the small `MuteResult` value class.
- **Controllers/Guards (6)**: Chat, ChatRoom, MentionDigest, RoomInvite,
  RoomMembershipGuard, RoomReadState.

### Cross-cutting duplication smells

- `if (userId == null || userId.isBlank()) return 401` appears in
  ~20 controller methods across `ChatRoomController`, `RoomInviteController`,
  and others — exactly the kind of cross-cutting check that belongs in
  a filter/aspect, not a method body.
- Service-level `Optional<X> + boolean ok` return patterns swallow the
  *reason* for failure: a caller cannot distinguish "not found" from
  "forbidden" from "muted" without re-reading the service.

---

## 3. Three stages — committed plan

### Stage 1 — Safety net (Mockito tests for 25 untested units)

**Why first**: the user's #1 requirement (regression minimization) needs a
test floor under everything Stages 2 and 3 will touch. Without this, the
controller/service shuffles in Stage 3 cannot be safely reviewed.

**Style**: Mockito-style unit tests, matching the existing convention
(`MessageSenderServiceMuteGateTest`-shaped). Dependency stubs over
`@SpringBootTest` slice tests — user explicitly chose this in brainstorming.

**Coverage target**:
- Per service: 1 happy path + 1–2 error/edge cases.
- Per controller: 200/4xx assertion per endpoint via `MockMvc`.

**Targets, prioritized by Stage 2/3 blast radius**:
1. `ChatRoomService` — Stage 3 splits this further; needs cache + persistence
   tests now.
2. `RoomMembershipService` — Stage 3-A will route the membership check
   through annotations; the service contract must be pinned first.
3. `MessagePinService`, `MessageReactionService` — Stage 3-B converts these
   to `Result<T,E>`; today's `boolean` contract must be locked.
4. `UnreadCountService`, `ReadReceiptService` — Stage 2's `ReadStateNotifier`
   on the frontend will pull harder on these; behavior must be characterized.
5. Remaining 20 units in any order — controllers last because they exercise
   services that already have tests by then.

**PRs**: 2.
- PR 1: 19 service tests.
- PR 2: 6 controller/guard tests.

**Estimate**: 2–3 days.

**Exit criteria**:
- `./gradlew :chat-service:test` passes with `tests=350+ failures=0 errors=0`.
- Each of the 25 units has at least one assertion that exercises its
  primary behavior contract.

---

### Stage 2 — Frontend decomposition

**Why second**: this is the largest pain (chat_messages_list at 2854 LOC,
chat_page at 1911 LOC with 62 touches in 90 days = nearly daily edits and
the merge-conflict epicenter). Stage 1 does not unblock this — frontend
tests are not in scope — so this stage carries its own risk via small PRs,
fresh code review per PR, and post-deploy smoke tests rather than a test
gate.

#### 2A — `chat_messages_list.dart` (2854 → ~400 container + N widgets)

Extract:
- `MessageBubble` (renderer + ownership + reactions + edit/delete affordances)
- `MessageMenu` (long-press / right-click popup, edit/delete/copy/forward/pin)
- `MessageReactionsRow` (emoji aggregations + tap-to-toggle)
- `DateDivider`, `UnreadDivider`
- `MessageMenuController` (the imperative `_showDeleteSheet` /
  `_showContextMenu` glue currently buried in private methods)

`ChatMessagesList` ends as a composition root: it owns the `ScrollController`
+ subscribes to the notifier + maps `state.messages` to the right child
widget, nothing else.

#### 2B — `chat_page.dart` (1911 → ~500 + extractions)

Extract:
- App bar (room header, member count, search, overflow menu)
- All bottom sheets and dialogs into their own widgets (Bookmark, Forward,
  Edit, Keyword, Pin list, Read-by, etc.)
- Scaffold-level keyboard shortcuts → `ChatPageShortcuts` widget

`ChatPage` ends as: scaffold + composed children + state-subscription glue.

#### 2C — `chat_notifier.dart` (1076 → ~400 + facades)

Split responsibilities into three notifier facades that share the same
state object via composition:
- `MessageNotifier`: send / edit / delete / forward / reply
- `ReadStateNotifier`: lastRead / unread / readers / mark-read
- `ConnectionNotifier`: STOMP connect / retry / heartbeat / online presence

The composition root remains a single `chatNotifierProvider(roomId)` that
delegates to the three facades — public API to widgets is preserved so
this is a strictly internal refactor.

**PRs**: 3 (one per file).

**Estimate**: 1–1.5 weeks.

**Exit criteria**:
- Each touched file ≤ 600 LOC.
- `flutter analyze` clean on touched files.
- Manual smoke test on app.chatflow.ai.kr post-deploy: open room → send →
  edit → delete → forward → react → reply thread → search → keyword alert.

---

### Stage 3 — Backend design patterns

#### 3-A — `@RequireAuth` / `@RequireMember` annotation + HandlerInterceptor

**Today**: 20+ controller methods each repeat
```java
if (userId == null || userId.isBlank()) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("인증이 필요합니다."));
}
```
or call `membershipGuard.requireMember(...)` and short-circuit on the
returned `ResponseEntity`. The control flow is mechanical and identical.

**After**:
- `@RequireAuth` annotation on controller methods. A `HandlerInterceptor`
  reads `X-User-Id` and short-circuits with 401 when missing.
- `@RequireMember(pathVar = "roomId")` builds on the same interceptor +
  the existing `RoomMembershipGuard`. The guard becomes the
  implementation, not the public surface.
- Controller methods receive `@AuthenticatedUser String userId` (resolved
  by an `ArgumentResolver`) so the `@RequestHeader(required = false)`
  null-check pattern dies.

**Open question** (decide during plan): annotation-driven `HandlerInterceptor`
vs Spring AOP `@Around`. Recommend interceptor — Spring MVC already
threads it on every request, no proxy magic.

#### 3-B — `Result<T, ErrorCode>` for every non-CRUD service

**Today**: `boolean deleteMessage(messageId, userId)` returns true on
success and false for *any* of: not found, not the author, deleted,
muted. The controller cannot decide between 403/404/410 — it returns
a single generic error.

**After**:
- Introduce a sealed `Result<T, E>` (or pull in Vavr `Either`; decide
  during planning — recommend a small in-repo sealed type to avoid the
  Vavr footprint).
- Define a `ChatErrorCode` enum: `NOT_FOUND`, `FORBIDDEN`, `MUTED`,
  `DELETED`, `ROOM_FULL`, `INVALID_INPUT`, …
- Convert every non-CRUD service that today returns `boolean` or
  `Optional<T>` + side-channel exception:
  `MessageEditService`, `MessagePinService`, `MessageReactionService`,
  `MessageReportService`, `RoomMembershipService.leaveRoom`,
  `InviteLinkService.resolveToken`, `LinkPreviewService.fetch`,
  `ScheduledMessageService.schedule`, plus the broadcasting outbox bits.
- `ChatErrorCode → HttpStatus` mapper lives once in a `@ControllerAdvice`
  or a small `ErrorResponses` helper.

**Scope confirmed**: user chose "넓게" (all non-CRUD services), so the PR
fan-out is wider — split by service domain, not all-at-once.

#### 3-C — `UserPresenceService` (254 LOC) decomposition

Four responsibilities in one file:
- `BanCheck` — pre-join ban gate
- `RoomFullness` — capacity decision + `alreadyJoined` exception
- `ParticipantRegistry` — Redis SET writes for presence
- `PresenceBroadcast` — JOIN system message + member-list publish

Extract three new services (the fourth keeps the existing name as the
orchestrator) so the orchestrator is ~80 LOC and each helper is small,
focused, and individually testable. Existing `UserPresenceService*Test`
classes stay green — they test through the orchestrator's public API.

**PRs**: 3 (3-A, 3-B split by service domain, 3-C).

**Estimate**: 1–1.5 weeks.

**Exit criteria**:
- Zero `if (userId == null) return 401` in controller bodies (grep-able).
- Every non-CRUD service signature returns `Result<…>` or throws an
  expected exception type (no more silent `boolean false`).
- `UserPresenceService` ≤ 100 LOC; each extracted helper ≤ 120 LOC.

---

## 4. Review and merge protocol

**Per the user's brainstorming decision**: every PR gets the
`superpowers:code-reviewer` agent first; only after that approval does
the PR merge into `develop`.

Workflow per PR:
1. Branch off `develop`, implement the slice.
2. `pre-push` hook runs touched-area tests locally (already wired).
3. `develop-build.yml` GH Actions runs full backend tests + lint.
4. `superpowers:code-reviewer` agent invocation against the PR diff.
5. Address review feedback, re-run reviewer.
6. Merge into `develop` (no GitHub PR UI — direct merge per current
   project convention).
7. After each stage's last PR merges: K3s staging rollout +
   `chatflow-qa` API-shape diff + manual smoke on app.chatflow.ai.kr.

Stage gating:
- Stage 2 does not start until Stage 1's two PRs are merged and the
  test floor (`tests=350+`) is on `develop`.
- Stage 3 does not start until Stage 2's three PRs are merged and the
  staging smoke is clean.

---

## 5. Non-goals (explicit)

- No frontend test framework introduction (Stage 1 stays backend-only —
  user chose "중간" depth, not "광범위").
- No MapStruct / domain-driven re-modeling of `ChatMessageEntity` /
  DTO mapping (mentioned as candidate but explicitly deferred —
  Stage 4-or-later).
- No package restructure (`com.chatflow.chat.*` stays as-is). Splits
  happen inside existing packages so import diffs stay small.
- No Vavr / functional-library footprint expansion unless 3-B planning
  proves the in-repo sealed type insufficient.

---

## 6. Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| Stage 2 PR conflicts with active feature work on `chat_page.dart` | Schedule Stage 2 PRs in a low-feature week; reviewer agent flags conflict patterns |
| `Result<T,E>` sealed type proliferates inconsistent error codes | Single `ChatErrorCode` enum + lint rule (Stage 3 PR-1 establishes the enum) |
| Stage 3-A interceptor swallows a path that needs `userId` for analytics only (not gating) | Tag-list of paths the interceptor must NOT short-circuit; integration test per path |
| Stage 1 tests pin behavior the refactor wants to change | Where Stage 2/3 changes the contract, Stage 1's test gets a parallel updated assertion in the same refactor PR — Stage 1 is a snapshot, not a freeze |

---

## 7. Total estimate

~3 weeks of focused work, distributed across 8 PRs:
- Stage 1: 2 PRs / 2–3 days
- Stage 2: 3 PRs / 1–1.5 weeks
- Stage 3: 3 PRs / 1–1.5 weeks
