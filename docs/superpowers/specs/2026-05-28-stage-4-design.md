# ChatFlow Stage 4 — Design (draft)

**Status**: DRAFT — pending user direction
**Date**: 2026-05-28
**Author**: assistant draft (no Q&A session — user requested direct proposal)
**Topic**: post-Stage-3 architectural follow-ups

> This is a draft proposal capturing three candidate Stage 4 work-streams.
> Stage 1-3 brainstorming followed the formal user-Q&A protocol; the user
> asked for a direct draft here to evaluate. Each section flags **open
> questions** that need user input before plan-writing can start.

---

## 1. Where Stage 3 ended

| Stage | Outcome | Net file count delta |
|-------|---------|----------------------|
| 1 | Backend safety net: 25 untested units gained Mockito tests (245 → 381 tests) | +25 test files |
| 2 | Frontend decomposition: 3 hotspot files extracted into 32 widgets/dialogs/helpers | +32 source files |
| 2.5 | `chat_notifier.dart` follow-up: 819 → 676 LOC via 2 internal helpers | +2 source files |
| 3 | Backend design patterns: annotation-driven auth (16 null-checks gone), `Result<T,ChatErrorCode>` for 6 services, `UserPresenceService` 254 → 85 LOC via 4-service decomposition | +12 source files, +5 test files |

**Total test count today**: 428 PASS (chat-service). Frontend has no widget-test framework.

**Remaining structural pain** (not addressed by Stage 1-3):
- DTO ↔ entity mapping is hand-written in every service (~20 occurrences) — duplication smell.
- Package layout is still `com.chatflow.chat.{controller, service, repository, entity, dto, exception, config, auth, result, service.presence}` — flat per layer, no feature/domain grouping. As features grow this scales poorly.
- The newly-extracted `presence/` subpackage (Stage 3-C) is the first feature-oriented subpackage. It works because the four classes share a clear domain. Other domains (`message-edit`, `pin`, `reaction`, `invite-link`, `link-preview`, `read-state`, `mute`, `ban`, `report`, `scheduled-message`, `bookmark`, `keyword`, …) are still mixed into the flat `service/` package.
- Common DTOs in `common/src/main/java/com/chatflow/common/dto/` are a grab-bag — `ChatMessage`, `ApiResponse`, `ErrorResponse`, `KafkaTopics`, `AuditEvent`. Some are wire contracts (shared across services), others are internal envelopes that don't need to be in `common`.

---

## 2. Three candidate work-streams

The user explicitly listed Stage 4 candidates earlier in the session:
- Domain modeling
- Package restructure
- MapStruct / DTO mapping framework

Each is described below with scope estimate and open questions.

### 2-A. Package restructure to feature-oriented layout (recommended first)

**Why**: The flat per-layer layout is the cheapest pain to fix and it sets the foundation for the other two work-streams. The `presence/` precedent (Stage 3-C) already established the pattern.

**Proposal**: Move every chat-service service + its tightly-coupled DTOs/exceptions into a feature subpackage:

```
com.chatflow.chat/
├── feature/
│   ├── message/                  # send/edit/delete/pin/reaction/edit-history/retention
│   ├── room/                     # create/get/dm/visibility/membership/invite-link
│   ├── presence/                 # ← already here (Stage 3-C)
│   ├── read/                     # unread-count, read-receipt, last-read
│   ├── moderation/               # ban, mute, report, audit
│   ├── ai/                       # ai-summary-broadcast (consumer side)
│   ├── notification/             # fcm, scheduled-message, mention-digest
│   ├── attachment/               # link-preview, file-upload
│   └── outbox/                   # outbox-event-repository, outbox-poller
├── controller/                   # stays flat — controllers cut across features
├── config/                       # WebMvcConfig, JwtAuthFilter, SecurityConfig
├── auth/                         # RequireAuth/Member/AuthenticatedUser/Interceptor
├── exception/                    # GlobalExceptionHandler
└── result/                       # Result, ChatErrorCode, ErrorResponses
```

**Constraints**:
- Public class names stay identical — only the package declaration moves. Import paths change repo-wide, but Java IDEs auto-rewrite imports on package move.
- Entities (`ChatMessageEntity`, `RoomMemberEntity`, etc.) stay in a flat `entity/` package for now — they're crosscutting and moving them would balloon the diff. Consider splitting them in a later stage.
- Common DTOs (`ChatMessage`, `ApiResponse`, `ErrorResponse`, `KafkaTopics`, `AuditEvent`) stay in `common/` — they're wire contracts shared with other microservices.

**Estimate**: 1 large PR (~30-40 files moved, no behavior change). Risk: low (mechanical move + import rewrite). Test diff is just package declaration changes.

**Open questions for user**:
1. Do we restructure all four chat-service entries (services, exceptions, internal helpers, repository) into features, or keep `repository/` and `entity/` flat?
2. Do we also restructure `gateway-service`, `ai-summary-service`, `search-service`? Or chat-service first as a pilot?
3. Are there any service classes you think DON'T fit cleanly into one feature bucket?

### 2-B. MapStruct / DTO mapping framework

**Why**: Today every controller hand-builds response DTOs from entities (e.g., `ChatMessageEntity → ChatMessage`, `ChatRoom → ChatRoomDto`). Hand-rolled mapping is duplicated, error-prone (silent missed fields when entity grows), and slow to test.

**Proposal**:
- Add MapStruct (compile-time mapper generator). Already in the Java ecosystem; no runtime reflection cost.
- Define one mapper interface per feature (e.g., `ChatMessageMapper`, `ChatRoomMapper`, `MessageEditHistoryMapper`).
- Generated `*Impl` classes go in `target/generated-sources/` — committed via Gradle config, not by hand.
- Existing hand-rolled mapping methods can be deleted incrementally as their callers migrate.

**Estimate**: 1 medium PR (foundation: Gradle config + 1 example mapper + 1 migrated controller) + N small PRs (1 per remaining mapper). Risk: low — MapStruct is mature and the generated code is straightforward.

**Open questions for user**:
1. MapStruct vs ModelMapper (runtime reflection) vs hand-roll vs Lombok `@SuperBuilder` patterns — should the comparison be done formally before adoption?
2. Should DTOs be in `common/` (wire contract) or in feature packages (chat-service internal)? Today the boundary is fuzzy — some DTOs are shared, some are response-only.
3. Is there appetite for the mapper *also* validating input (e.g., reject `ChatMessage.content == null` at the mapping layer)?

### 2-C. Domain modeling

**Why**: The current `ChatMessageEntity` is a flat row with 30+ columns covering CHAT/SYSTEM/JOIN/LEAVE/AI_SUMMARY/PATIENT_CARD/FILE/etc. Some columns are only meaningful for one type (e.g., `reactions` JSON, `pinned`, `aiQuestion`). This was deferred in Stage 3 (`spec` mentioned "MapStruct / domain-driven re-modeling … deferred to Stage 4-or-later").

**Proposal**:
- Introduce typed domain objects: `Message`, `SystemMessage`, `AiMessage`, `FileMessage`, etc. backed by either:
  - (a) single-table inheritance via discriminator (JPA `@DiscriminatorColumn`) — minimal schema change
  - (b) shared `Message` base + side tables for type-specific fields — cleaner but bigger schema migration
- Service signatures become type-safe: `MessageEditService.edit(Message)` vs `MessageEditService.edit(SystemMessage)` is a compile error if a controller tries to edit a system message.

**Estimate**: 1 large brainstorm + 1 design spec + 3-5 PRs (schema migration + entity refactor + service signature updates + DTO mapping update + test migration). Risk: **medium-high** — touches schema, JPA, and every caller.

**Open questions for user**:
1. Is the current `ChatMessageEntity` actually causing concrete bugs/inefficiencies, or is it just architectural smell? If only the latter, this work is low-priority.
2. Single-table inheritance vs side-tables — which is acceptable given that messages are written 10x more than read?
3. Is there a frontend / Flutter implication (DTO shape would change)? PR-2A established that frontend's `ChatMessage` model handles a flat shape; switching to typed messages forces a frontend migration too.

---

## 3. Recommended sequencing (if all three are wanted)

1. **2-A (package restructure)** — first, because it scopes the surface for 2-B and 2-C without changing behavior. Lowest risk.
2. **2-B (MapStruct)** — second, because it consolidates the boilerplate that 2-C would otherwise touch.
3. **2-C (domain modeling)** — last, because it depends on (a) clear feature boundaries (from 2-A) and (b) a mapping layer (from 2-B). Also requires the most user decision-making.

---

## 4. What's NOT in scope for Stage 4

- Frontend test framework introduction — explicit Stage 3 non-goal, no signal to revisit.
- gateway-service / ai-summary-service / search-service refactor — chat-service is the hot path; the others get pulled in only if Stage 4-A is greenlit and the user wants the full repo.
- STOMP `ChatController.isMember` migration to annotation interceptor — Stage 3 non-goal preserved (different dispatch path).
- Performance work (caching, sharding, snowflake ID adoption) — separate track per `reference_messaging_architecture.md` (Production Messaging Architecture reference). Not refactoring.

---

## 5. Decision needed

Before this draft becomes a plan, the user must pick:

- **(A) which of 2-A / 2-B / 2-C to do next** (or none of them — Stage 3 is a natural stopping point),
- **(B) whether to do them sequentially or pick just one**,
- **(C) answers to the per-section open questions above**.

If 2-A is chosen as the first work-stream, the next step is a formal `superpowers:brainstorming` session to lock the package map and decide entity placement. The plan then flows to `superpowers:writing-plans` and `superpowers:subagent-driven-development` per the Stage 1-3 pattern.

---

## 6. Status

- Draft committed at `docs/superpowers/specs/2026-05-28-stage-4-design.md`.
- Spec self-review NOT performed (this is a pre-brainstorm draft, not a finalized spec).
- User review gate: **open** — no implementation will start without explicit user direction on §5.
