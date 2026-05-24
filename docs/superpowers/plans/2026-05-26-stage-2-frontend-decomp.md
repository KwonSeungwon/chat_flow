# Stage 2 — Frontend Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose three frontend hotspot files (chat_messages_list 2854 LOC, chat_page 1911 LOC, chat_notifier 1076 LOC) into focused units ≤ 600 LOC each, without changing any user-visible behavior.

**Architecture:** Each PR is a pure file-move refactor. The big files become composition roots that import the extracted widgets/helpers. Public API (the `chatNotifierProvider`, the `ChatMessagesList` constructor, the `ChatPage` route) is preserved. Stage 1's 381 backend tests are the backstop for any inadvertent API contract breaks; smoke-test on staging is the backstop for any frontend UX regressions.

**Tech Stack:** Flutter 3.22+, Dart 3.3+, Riverpod 2.5 (`StateNotifier`), GoRouter 14, `stomp_dart_client` 2, `flutter_secure_storage` 9.

**Source spec:** `docs/superpowers/specs/2026-05-24-refactor-three-stage-design.md` (Stage 2 section).

**Stage 1 baseline:** `develop` tip `c60cadc`, backend tests = 381 PASS.

**Branches (one per PR):**
- PR-2A: `refactor/stage-2a-messages-list` — chat_messages_list.dart split
- PR-2B: `refactor/stage-2b-chat-page` — chat_page.dart split
- PR-2C: `refactor/stage-2c-notifier-facades` — chat_notifier.dart split

**Per-PR exit criteria:**
- Each touched/created file ≤ 600 LOC
- `flutter analyze` clean on all touched files (no NEW warnings; pre-existing infos in unrelated lines OK)
- Manual smoke test on staging URL `https://app.chatflow.ai.kr` PASS:
  open room → send → edit → delete → forward → react → reply thread → search → keyword alert

**Reviewer cadence:** every PR ends with `superpowers:code-reviewer` agent verifying file-size budget + flutter-analyze + no public-API drift, then merges directly into `develop`.

---

## Reference patterns (read once before any task)

### Pattern A — Widget file extraction

The `chat_messages_list.dart` file already has 13 private widgets, each a `class _Foo extends StatelessWidget` (or `StatefulWidget` + state class). Extraction recipe:

1. Identify the source line range of the class (start at `class _Foo` ending at the matching `}`).
2. Create a new file `frontend/lib/features/chat/widgets/<snake_case_name>.dart`.
3. Top-of-file imports: copy the imports from `chat_messages_list.dart` that the extracted class actually references. Drop unused imports.
4. Rename `_Foo` → `Foo` (drop the leading underscore so it's importable). Same for `_FooState` → `FooState` (state class stays public because Dart requires it to match constructor visibility).
5. Delete the original class block from `chat_messages_list.dart`. Add `import 'widgets/<snake_case_name>.dart';` at the top.
6. Replace any `_Foo(...)` constructor calls inside `chat_messages_list.dart` with `Foo(...)`.

### Pattern B — Top-level function extraction (chat_page.dart)

`chat_page.dart` declares dialog functions at the top level (`void _showXxx(BuildContext, WidgetRef, ...)`). Extraction recipe:

1. Move the function (e.g. `_showProfileDialog`) into a new file `frontend/lib/features/chat/dialogs/<snake_case>.dart`.
2. Rename `_showXxx` → `showXxx` (drop underscore; top-level functions need to be importable).
3. Copy only the imports the function actually uses.
4. Replace call sites in `chat_page.dart` with `showXxx(context, ref, ...)`.

### Pattern C — Notifier facade extraction (chat_notifier.dart)

ChatNotifier is a single 937-LOC StateNotifier. Public API (the `chatNotifierProvider` and ~20 public methods called from widgets) MUST stay on `ChatNotifier`. Internal helpers move to package-private classes constructed inside `ChatNotifier.__init__`-equivalent and called from the public methods.

Recipe per facade:
1. Identify a cohesive method cluster (e.g. all read-state methods + their state fields).
2. Create `frontend/lib/features/chat/internal/<facade>.dart` with a package-private class (e.g. `class ReadStateHelper`) holding the cluster's *internal* state fields and helper methods.
3. ChatNotifier instantiates the helper in its constructor with closures over its own `_ref`, `state`, `_stompService`, etc.
4. Each public ChatNotifier method that previously did the work now delegates one line to `_readState.markRead(...)`.

This is the riskiest of the three PRs because StateNotifier's mutable `state` field is shared. See PR-2C tasks for the explicit closure-passing pattern.

---

# PR-2A — chat_messages_list.dart decomposition

**Goal:** Move 12 internal widgets out of `chat_messages_list.dart` into their own files. Leave only `ChatMessagesList` + `_ChatMessagesListState` (the composition root) in the original file.

**Source line map** (read once):

| Lines | Class | Target file |
|-------|-------|-------------|
| 18–660 | `ChatMessagesList` + state (KEEP HERE) | (unchanged) |
| 661–723 | `_SystemBubble` | `widgets/bubbles/system_bubble.dart` |
| 724–914 | `_AiSummaryCard` + state + `_TypingDots` + state | `widgets/bubbles/ai_summary_bubble.dart` (group AI-related) |
| 915–996 | `_AiLoadingBubble` | merge into `ai_summary_bubble.dart` (same AI cluster) |
| 997–1972 | `_ChatBubble` + state (975 LOC — biggest) | `widgets/bubbles/chat_bubble.dart` |
| 1973–2005 | `_HoverReactionBar` | `widgets/bubbles/hover_reaction_bar.dart` |
| 2006–2033 | `_UnreadDivider` | `widgets/dividers/unread_divider.dart` |
| 2034–2103 | `_SbarCardWidget` | `widgets/bubbles/sbar_card.dart` |
| 2104–2281 | `_LinkPreviewCard` + state | `widgets/bubbles/link_preview_card.dart` |
| 2282–2322 | `_DateDivider` | `widgets/dividers/date_divider.dart` |
| 2323–2431 | `_PatientCardBubble` | `widgets/bubbles/patient_card_bubble.dart` |
| 2432–2466 | `_Avatar` | `widgets/bubbles/avatar.dart` |
| 2467–end | `_FileBubble` | `widgets/bubbles/file_bubble.dart` |

Estimated post-split sizes: ChatBubble (~975), ChatMessagesList (~640), FileBubble (~390), LinkPreviewCard (~180), AI bundle (~270), PatientCardBubble (~108). ChatBubble is the one risk — it's still ~975 LOC. If it's still over budget after the simple class move, a follow-up sub-task in PR-2A breaks the menu logic (`_showContextMenu`, `_showDeleteSheet`) into a `chat_bubble_menu.dart` companion.

## Task 1: Branch off develop

**Files:** none (git only).

- [ ] **Step 1: Create branch**

```bash
git checkout develop && git pull --ff-only origin develop
git checkout -b refactor/stage-2a-messages-list
```

- [ ] **Step 2: Verify clean tree**

```bash
git status
```
Expected: `nothing to commit, working tree clean`. develop tip should be `c60cadc` (Stage 1 close).

## Task 2: Extract SystemBubble

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/system_bubble.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart` (delete lines 661-723, add import + rename references)

- [ ] **Step 1: Inspect the source class**

```bash
sed -n '661,723p' frontend/lib/features/chat/widgets/chat_messages_list.dart
```
Read what it imports / references. `_SystemBubble` shows a system message centered in the chat. Should reference `ChatMessage` and Material theme — no STOMP, no provider.

- [ ] **Step 2: Create the new file**

Header template:
```dart
import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';

// Paste lines 661-723 from chat_messages_list.dart here.
// Rename: `class _SystemBubble` → `class SystemBubble`.
// All internal references within this file (none expected, but check)
// stay the same.
```

- [ ] **Step 3: Remove the original class from chat_messages_list.dart**

Delete lines 661-723 (the `class _SystemBubble extends StatelessWidget { ... }` block).

- [ ] **Step 4: Add import + update call sites**

At the top of `chat_messages_list.dart`, add (alphabetical order):
```dart
import 'bubbles/system_bubble.dart';
```

Then `grep -n "_SystemBubble" frontend/lib/features/chat/widgets/chat_messages_list.dart` to find every constructor call site. Replace each `_SystemBubble(...)` with `SystemBubble(...)`.

- [ ] **Step 5: Verify**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/bubbles/system_bubble.dart lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -10
```
Expected: zero new errors. Pre-existing info-level lints in unrelated lines OK.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/features/chat/widgets/bubbles/system_bubble.dart \
        frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract SystemBubble from chat_messages_list"
```

## Task 3: Extract AI cluster (AiSummaryCard + TypingDots + AiLoadingBubble)

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/ai_summary_bubble.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart` (delete lines 724-996, add import + rename refs)

These three classes form one AI rendering cluster — they share theme conventions and only call each other. Grouping them into one file keeps the cluster's seams clean.

- [ ] **Step 1: Inspect**

```bash
sed -n '724,996p' frontend/lib/features/chat/widgets/chat_messages_list.dart | head -50
```

- [ ] **Step 2: Create the new file**

```dart
import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';

// Paste lines 724-996 from chat_messages_list.dart.
// Renames:
//   _AiSummaryCard       → AiSummaryCard
//   _AiSummaryCardState  → AiSummaryCardState
//   _TypingDots          → TypingDots
//   _TypingDotsState     → TypingDotsState
//   _AiLoadingBubble     → AiLoadingBubble
// If _TypingDots or _AiLoadingBubble is referenced ONLY from the
// classes in this same file, the rename inside the file is a
// simple text find-replace.
```

- [ ] **Step 3: Remove originals + add import + update call sites**

Delete lines 724-996 from `chat_messages_list.dart`. Add import:
```dart
import 'bubbles/ai_summary_bubble.dart';
```
Run `grep -n "_AiSummaryCard\|_TypingDots\|_AiLoadingBubble" frontend/lib/features/chat/widgets/chat_messages_list.dart` and rename all call sites.

- [ ] **Step 4: Verify**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/bubbles/ai_summary_bubble.dart lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/widgets/bubbles/ai_summary_bubble.dart \
        frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract AI cluster (summary card + typing dots + loading) from chat_messages_list"
```

## Task 4: Extract Avatar

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/avatar.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

- [ ] **Step 1: Inspect**

```bash
sed -n '2432,2466p' frontend/lib/features/chat/widgets/chat_messages_list.dart
```

- [ ] **Step 2: Create the new file**

```dart
import 'package:flutter/material.dart';

// Paste lines 2432-2466.
// Rename: _Avatar → Avatar.
// Helper top-level function `_avatarColor(...)` (if also moved here)
// becomes `avatarColor(...)` and is exported alongside.
```

Run `grep -n "_avatarColor" frontend/lib/features/chat/widgets/chat_messages_list.dart` first — if the function is defined elsewhere in the same file, decide whether to move it with `_Avatar` or leave it. The simpler choice: move both to `avatar.dart` since `_Avatar` is the sole caller.

- [ ] **Step 3: Remove from original + import + rename refs**

Same recipe as Task 2.

- [ ] **Step 4-5: Verify + commit**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/bubbles/avatar.dart lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -10
git add frontend/lib/features/chat/widgets/bubbles/avatar.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract Avatar from chat_messages_list"
```

## Task 5: Extract dividers (DateDivider + UnreadDivider)

**Files:**
- Create: `frontend/lib/features/chat/widgets/dividers/date_divider.dart`
- Create: `frontend/lib/features/chat/widgets/dividers/unread_divider.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

Two tiny widgets (~70 LOC together). One commit, two files for cleanness.

- [ ] **Step 1: Create date_divider.dart**

```dart
import 'package:flutter/material.dart';

// Paste lines 2282-2322. Rename _DateDivider → DateDivider.
```

- [ ] **Step 2: Create unread_divider.dart**

```dart
import 'package:flutter/material.dart';

// Paste lines 2006-2033. Rename _UnreadDivider → UnreadDivider.
```

- [ ] **Step 3: Remove originals + imports + rename refs**

Delete lines 2006-2033 AND lines 2282-2322 from `chat_messages_list.dart`. Add two imports. Rename call sites.

- [ ] **Step 4-5: Verify + commit**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/dividers/ lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -10
git add frontend/lib/features/chat/widgets/dividers/ frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract Date/Unread dividers from chat_messages_list"
```

## Task 6: Extract HoverReactionBar

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/hover_reaction_bar.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

- [ ] **Step 1: Inspect + extract**

```bash
sed -n '1973,2005p' frontend/lib/features/chat/widgets/chat_messages_list.dart
```

```dart
import 'package:flutter/material.dart';

// Paste lines 1973-2005. Rename _HoverReactionBar → HoverReactionBar.
```

- [ ] **Step 2: Remove + import + rename refs**

- [ ] **Step 3: Verify + commit**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/bubbles/hover_reaction_bar.dart lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -10
git add frontend/lib/features/chat/widgets/bubbles/hover_reaction_bar.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract HoverReactionBar from chat_messages_list"
```

## Task 7: Extract SbarCardWidget

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/sbar_card.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

- [ ] **Step 1: Inspect + extract**

```bash
sed -n '2034,2103p' frontend/lib/features/chat/widgets/chat_messages_list.dart
```

```dart
import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';

// Paste lines 2034-2103. Rename _SbarCardWidget → SbarCard.
// (Drop the redundant "Widget" suffix — every Flutter class is a widget.)
```

- [ ] **Step 2-4: Remove + import + rename refs + verify + commit**

```bash
git add frontend/lib/features/chat/widgets/bubbles/sbar_card.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract SbarCard from chat_messages_list"
```

## Task 8: Extract LinkPreviewCard

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/link_preview_card.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

- [ ] **Step 1: Inspect**

```bash
sed -n '2104,2281p' frontend/lib/features/chat/widgets/chat_messages_list.dart | head -40
```

This widget is a StatefulWidget that fetches link previews via Dio. Imports needed: `flutter/material.dart`, the `dio_client` provider, and any model classes.

- [ ] **Step 2: Create the new file**

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/network/dio_client.dart';

// Paste lines 2104-2281.
// Renames:
//   _LinkPreviewCard      → LinkPreviewCard
//   _LinkPreviewCardState → LinkPreviewCardState
```

- [ ] **Step 3-5: Remove + import + rename refs + verify + commit**

```bash
git add frontend/lib/features/chat/widgets/bubbles/link_preview_card.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract LinkPreviewCard from chat_messages_list"
```

## Task 9: Extract PatientCardBubble

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/patient_card_bubble.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

- [ ] **Step 1-5: Same recipe — lines 2323-2431, rename `_PatientCardBubble` → `PatientCardBubble`**

```bash
git add frontend/lib/features/chat/widgets/bubbles/patient_card_bubble.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract PatientCardBubble from chat_messages_list"
```

## Task 10: Extract FileBubble

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/file_bubble.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

- [ ] **Step 1: Inspect** — `_FileBubble` is large (~387 LOC) and handles image preview + download. Will land near the 400 LOC budget on its own.

```bash
sed -n '2467,2854p' frontend/lib/features/chat/widgets/chat_messages_list.dart | head -50
```

Identify imports it needs: `dart:io` (mobile only? guard with `kIsWeb`), `flutter/material.dart`, `dio` for download, `path_provider`, etc.

- [ ] **Step 2-5: Same recipe**

```bash
git add frontend/lib/features/chat/widgets/bubbles/file_bubble.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract FileBubble from chat_messages_list"
```

## Task 11: Extract ChatBubble + ChatBubbleMenu

**Files:**
- Create: `frontend/lib/features/chat/widgets/bubbles/chat_bubble.dart`
- Create: `frontend/lib/features/chat/widgets/bubbles/chat_bubble_menu.dart` (if ChatBubble > 600 LOC after move)
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart`

The biggest extraction. `_ChatBubble + _ChatBubbleState` is ~975 LOC, contains `_showContextMenu`, `_showDeleteSheet`, `_showEmojiPicker`, all the long-press / right-click handling.

- [ ] **Step 1: Move ChatBubble as-is into chat_bubble.dart**

```bash
sed -n '997,1972p' frontend/lib/features/chat/widgets/chat_messages_list.dart | wc -l
```
Confirm ~975 LOC.

Create `chat_bubble.dart`:
```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../shared/models/chat_message.dart';
import '../../../../core/network/dio_client.dart';
import 'avatar.dart';
import 'hover_reaction_bar.dart';
import 'file_bubble.dart';
import 'link_preview_card.dart';
import 'patient_card_bubble.dart';
import 'sbar_card.dart';

// Paste lines 997-1972. Renames:
//   _ChatBubble      → ChatBubble
//   _ChatBubbleState → ChatBubbleState
// Internal references to the just-extracted siblings already use
// the unprefixed names from the earlier tasks.
```

- [ ] **Step 2: Measure**

```bash
wc -l frontend/lib/features/chat/widgets/bubbles/chat_bubble.dart
```
If ≤ 600 LOC, skip the menu split — move to Step 5.
If > 600 LOC, do Step 3.

- [ ] **Step 3 (conditional): Extract menu logic into chat_bubble_menu.dart**

Identify the menu methods inside `ChatBubbleState`:
- `void _showContextMenu(BuildContext context, Offset position)`
- `void _showDeleteSheet(BuildContext context)`
- `void _showEmojiPicker(BuildContext context)`

Move them to a free-standing `chat_bubble_menu.dart`:
```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../shared/models/chat_message.dart';

class ChatBubbleMenu {
  static void showContextMenu(
    BuildContext context,
    Offset position,
    ChatMessage msg,
    /* all the callbacks ChatBubble currently passes */
  ) { /* function body moved verbatim from _showContextMenu */ }

  static void showDeleteSheet(BuildContext context, ChatMessage msg, /*...*/) { /* ... */ }

  static void showEmojiPicker(BuildContext context, /*...*/) { /* ... */ }
}
```

In `ChatBubbleState`, replace the three method bodies with `ChatBubbleMenu.showContextMenu(context, position, widget.msg, ...)`. Pass the same closures (onEdit, onDelete, onReply, etc.) the original method bodies used to read from `widget`.

- [ ] **Step 4 (conditional): Re-measure**

```bash
wc -l frontend/lib/features/chat/widgets/bubbles/chat_bubble.dart frontend/lib/features/chat/widgets/bubbles/chat_bubble_menu.dart
```
Both files should be ≤ 600 LOC.

- [ ] **Step 5: Remove originals from chat_messages_list + update imports + rename call sites**

Delete lines 997-1972 from `chat_messages_list.dart`. Add `import 'bubbles/chat_bubble.dart';`. Rename every `_ChatBubble(...)` → `ChatBubble(...)`.

- [ ] **Step 6: Verify**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/bubbles/ lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -15
```

- [ ] **Step 7: Commit**

If single-file split:
```bash
git add frontend/lib/features/chat/widgets/bubbles/chat_bubble.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract ChatBubble from chat_messages_list"
```
If menu split happened:
```bash
git add frontend/lib/features/chat/widgets/bubbles/chat_bubble.dart frontend/lib/features/chat/widgets/bubbles/chat_bubble_menu.dart frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "refactor(frontend): extract ChatBubble + ChatBubbleMenu from chat_messages_list"
```

## Task 12: PR-2A size budget audit

- [ ] **Step 1: Measure all touched files**

```bash
wc -l frontend/lib/features/chat/widgets/chat_messages_list.dart \
      frontend/lib/features/chat/widgets/bubbles/*.dart \
      frontend/lib/features/chat/widgets/dividers/*.dart
```

Expected:
- `chat_messages_list.dart` ≤ 600 LOC (target: ~640 minus a few `_` → no-prefix delta — should land near 620, close to budget; if over, file an issue or extract `_ChatMessagesListState`'s scroll handlers into a mixin and revisit)
- All bubbles/ files ≤ 600 LOC
- All dividers/ files ≤ 600 LOC

If any file is over budget, file a follow-up issue but DO NOT block PR-2A — Stage 2 plan accepts iterative refinement.

- [ ] **Step 2: Full flutter analyze**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/ 2>&1 | tail -15
```
Expected: zero NEW errors. Pre-existing infos in unrelated files OK.

## Task 13: Manual smoke test on staging

- [ ] **Step 1: Build and deploy the branch**

This requires a manual local-then-deploy flow because PR-2A is large enough that running it locally first is prudent.

```bash
cd frontend && flutter run -d chrome --release
```
Open `http://localhost:<port>`. Run through the smoke checklist:
- Open a room with 20+ messages → confirm rendering identical to develop
- Send a new message → bubble appears, hover/long-press menu works
- Edit own message → "(수정됨)" badge appears
- Delete own message → tombstone shows
- Forward a message → forward dialog opens
- React (emoji) → emoji aggregation chip shows; toggle clears it
- Open reply thread → modal opens, send a reply, parent's reply count chip increments
- Search inside room → jumps to matched message
- Add a keyword to the room → send a message containing it from another browser/account → in-app banner

If anything visibly breaks: stop, file a bug, revert.

- [ ] **Step 2: (Optional) Deploy to staging via GH Actions**

If the local smoke is clean and you want a staging round, manually trigger the `Deploy to K3s (manual)` workflow for `frontend` with the branch's auto-built image. Not strictly required since this PR is test-equivalent risk.

## Task 14: PR-2A code review

- [ ] **Step 1: Push branch**

```bash
git push -u origin refactor/stage-2a-messages-list
```

- [ ] **Step 2: Invoke superpowers:code-reviewer**

Prompt:
> Review branch `refactor/stage-2a-messages-list` against `develop`.
> Verify:
> 1. `git diff develop..HEAD -- frontend/lib/features/chat/widgets/chat_messages_list.dart`
>    shows ONLY class deletions + import additions + name renames. NO
>    behavior changes inside method bodies.
> 2. Each newly created file under `frontend/lib/features/chat/widgets/bubbles/`
>    and `dividers/` corresponds to exactly one class block that was
>    previously inside chat_messages_list.dart.
> 3. Every file ≤ 600 LOC (use `wc -l`).
> 4. `flutter analyze` on touched files: zero NEW errors. Pre-existing
>    info lints in untouched files OK.
> 5. The constructor signature of every renamed class matches the
>    original (e.g. `_ChatBubble({required this.msg, ...})` →
>    `ChatBubble({required this.msg, ...})` — same param list, same
>    `required` markers).
> 6. ChatMessagesList's public API (constructor params) is unchanged.

- [ ] **Step 3: Address feedback if any**

## Task 15: Merge PR-2A into develop

- [ ] **Step 1: Merge**

```bash
git checkout develop && git pull --ff-only origin develop
git merge --no-ff refactor/stage-2a-messages-list -m "$(cat <<'EOF'
Merge refactor/stage-2a-messages-list into develop

Stage 2 PR-2A: chat_messages_list.dart 2854 LOC decomposed into 12
focused widget files. ChatMessagesList container ≤ 600 LOC. No
behavior changes — pure file-move + public-rename refactor.

Constraint: Stage 1 backend safety net (381 tests) is the regression
floor for the API contract; frontend smoke test on staging is the
floor for UX.
Confidence: high
Scope-risk: narrow — every change is a class move + name rename.
EOF
)"
git push origin develop
```

- [ ] **Step 2: Verify develop-build.yml succeeds**

```bash
gh run list --workflow=develop-build.yml --limit 1
```

---

# PR-2B — chat_page.dart decomposition

**Goal:** Move dialogs, modals, and small widgets out of `chat_page.dart` into separate files. Leave only `ChatPage` + `_ChatRoomContent` (the composition root) in the original file.

**Source line map**:

| Lines | Element | Target file |
|-------|---------|-------------|
| 72–158 | `_showProfileDialog` | `dialogs/profile_dialog.dart` |
| 159–265 | `_showBookmarksDialog` | `dialogs/bookmarks_dialog.dart` |
| 266–393 | `_showRoomSettingsDialog` | `dialogs/room_settings_dialog.dart` |
| 394–456 | `_showForwardDialog` | `dialogs/forward_dialog.dart` |
| 457–473 | `_showInRoomSearch` | `dialogs/in_room_search.dart` |
| 474–533 | `_showReadersSheet` | `dialogs/readers_sheet.dart` |
| 534–921 | `ChatPage` | (KEEP HERE) |
| 922–972 | `_ParticipantBadge` + `_showModal` | `widgets/participant_badge.dart` |
| 973–1463 | `_ChatRoomContent` + state + `_showEditDialog` | (KEEP HERE + extract `_showEditDialog`) |
| 1464–1529 | `_LobbyPlaceholder` | `widgets/lobby_placeholder.dart` |
| 1530–1586 | `_AiSummaryButton` | `widgets/ai_summary_button.dart` |
| 1587–1615 | `_ProfileAvatar` | `widgets/profile_avatar.dart` |
| 1616–1833 | `_InviteMemberModal` | `widgets/invite_member_modal.dart` |
| 1834–1911 | `_ConnectionDot` + `_BouncingDots` | `widgets/connection_dot.dart` |

Estimated post-split sizes: ChatPage core (~387) + _ChatRoomContent (~490) + small leftover = ~900 LOC in chat_page.dart total. If still over 600, _ChatRoomContent gets extracted to its own file in a final sub-task.

## Task 16: Branch off develop (PR-2B)

- [ ] **Step 1: Branch**

```bash
git checkout develop && git pull --ff-only origin develop
git checkout -b refactor/stage-2b-chat-page
```

## Task 17: Extract 6 dialogs/sheets into dialogs/

**Files:**
- Create: `frontend/lib/features/chat/dialogs/profile_dialog.dart`
- Create: `frontend/lib/features/chat/dialogs/bookmarks_dialog.dart`
- Create: `frontend/lib/features/chat/dialogs/room_settings_dialog.dart`
- Create: `frontend/lib/features/chat/dialogs/forward_dialog.dart`
- Create: `frontend/lib/features/chat/dialogs/in_room_search.dart`
- Create: `frontend/lib/features/chat/dialogs/readers_sheet.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

Six dialogs in one task because the pattern is identical and they're independent.

- [ ] **Step 1: Per-dialog extraction recipe**

For each dialog (start with `_showProfileDialog` at line 72):

1. Read the function block:
   ```bash
   sed -n '72,158p' frontend/lib/features/chat/chat_page.dart
   ```
2. Create `dialogs/profile_dialog.dart`:
   ```dart
   import 'package:flutter/material.dart';
   import 'package:flutter_riverpod/flutter_riverpod.dart';
   // ... whatever the function actually imports

   void showProfileDialog(BuildContext context, WidgetRef ref) {
     // Paste lines 72-158 body (everything between the opening `{` and matching `}`).
   }
   ```
3. Replace the original call site in `chat_page.dart`. The function was called via `_showProfileDialog(context, ref)`. After extraction, callers say `showProfileDialog(context, ref)`. Add the import.

- [ ] **Step 2: Repeat for each of the 6 dialogs**

Source ranges:
- `_showProfileDialog` 72–158 → `showProfileDialog(BuildContext, WidgetRef)` in `profile_dialog.dart`
- `_showBookmarksDialog` 159–265 → `showBookmarksDialog(BuildContext, WidgetRef)` in `bookmarks_dialog.dart`
- `_showRoomSettingsDialog` 266–393 → `showRoomSettingsDialog(BuildContext, WidgetRef, String roomId, ChatRoom room)` in `room_settings_dialog.dart`
- `_showForwardDialog` 394–456 → `showForwardDialog(BuildContext, WidgetRef, ChatNotifier, ChatMessage)` in `forward_dialog.dart`
- `_showInRoomSearch` 457–473 → `showInRoomSearch(BuildContext, WidgetRef, String roomId)` in `in_room_search.dart`
- `_showReadersSheet` 474–533 → `showReadersSheet(BuildContext, WidgetRef, String roomId, String messageId, List<ChatMessage>)` in `readers_sheet.dart`

- [ ] **Step 3: Delete originals from chat_page.dart**

Delete lines 72–533 (one contiguous block holding all six dialog functions). Add six imports at the top.

- [ ] **Step 4: Verify**

```bash
cd frontend && flutter analyze lib/features/chat/dialogs/ lib/features/chat/chat_page.dart 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/dialogs/ frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract 6 dialogs/sheets from chat_page"
```

## Task 18: Extract _ParticipantBadge (+ its _showModal helper)

**Files:**
- Create: `frontend/lib/features/chat/widgets/participant_badge.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

- [ ] **Step 1-5: Same widget-extraction recipe**

Lines 922-972 (after Task 17 shifts them). Rename `_ParticipantBadge` → `ParticipantBadge`. The `_showModal` method is inside the class; it stays inside but the class is now public.

```bash
git add frontend/lib/features/chat/widgets/participant_badge.dart frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract ParticipantBadge from chat_page"
```

## Task 19: Extract _LobbyPlaceholder

**Files:**
- Create: `frontend/lib/features/chat/widgets/lobby_placeholder.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

- [ ] **Step 1-5: Same recipe — small (~65 LOC)**

```bash
git add frontend/lib/features/chat/widgets/lobby_placeholder.dart frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract LobbyPlaceholder from chat_page"
```

## Task 20: Extract _AiSummaryButton + _ProfileAvatar

**Files:**
- Create: `frontend/lib/features/chat/widgets/ai_summary_button.dart`
- Create: `frontend/lib/features/chat/widgets/profile_avatar.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

Two small widgets in one task (~56 + ~28 LOC).

- [ ] **Step 1-5: Same recipe**

```bash
git add frontend/lib/features/chat/widgets/ai_summary_button.dart \
        frontend/lib/features/chat/widgets/profile_avatar.dart \
        frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract AiSummaryButton + ProfileAvatar from chat_page"
```

## Task 21: Extract _InviteMemberModal

**Files:**
- Create: `frontend/lib/features/chat/widgets/invite_member_modal.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

Largest extraction in PR-2B (~217 LOC). StatefulWidget with Dio calls — copy needed imports carefully.

- [ ] **Step 1-5: Same recipe**

```bash
git add frontend/lib/features/chat/widgets/invite_member_modal.dart frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract InviteMemberModal from chat_page"
```

## Task 22: Extract _ConnectionDot + _BouncingDots

**Files:**
- Create: `frontend/lib/features/chat/widgets/connection_dot.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

Two related animation widgets — keep together.

- [ ] **Step 1-5: Same recipe — ~78 LOC**

```bash
git add frontend/lib/features/chat/widgets/connection_dot.dart frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract ConnectionDot + BouncingDots from chat_page"
```

## Task 23: Extract _showEditDialog from _ChatRoomContent

**Files:**
- Create: `frontend/lib/features/chat/dialogs/edit_message_dialog.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart`

`_showEditDialog` is a method on `_ChatRoomContentState` (lines ~1418–1458). Pull it out as a free function so `_ChatRoomContent` shrinks.

- [ ] **Step 1: Inspect**

```bash
grep -n "_showEditDialog" frontend/lib/features/chat/chat_page.dart
```
Confirm it's only called from one place inside `_ChatRoomContent.build()`.

- [ ] **Step 2: Create dialogs/edit_message_dialog.dart**

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../chat_notifier.dart';
import '../chat_provider.dart';

void showEditMessageDialog(
  BuildContext context,
  WidgetRef ref,
  String roomId,
  String messageId,
  String currentContent,
) {
  // Paste method body verbatim. Replace `chatNotifierProvider` references
  // that previously came through `widget` with explicit `ref` reads.
}
```

- [ ] **Step 3: Delete from _ChatRoomContent + update call site**

In `_ChatRoomContent.build()`, replace `_showEditDialog(context, ref, roomId, messageId, currentContent)` with `showEditMessageDialog(context, ref, roomId, messageId, currentContent)`.

- [ ] **Step 4-5: Verify + commit**

```bash
git add frontend/lib/features/chat/dialogs/edit_message_dialog.dart frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract showEditMessageDialog from _ChatRoomContent"
```

## Task 24: PR-2B size budget audit

- [ ] **Step 1: Measure**

```bash
wc -l frontend/lib/features/chat/chat_page.dart \
      frontend/lib/features/chat/dialogs/*.dart \
      frontend/lib/features/chat/widgets/{participant_badge,lobby_placeholder,ai_summary_button,profile_avatar,invite_member_modal,connection_dot}.dart
```

Expected: chat_page.dart down to ~900 LOC. Target was 500 — over budget. Decision point:

- If chat_page.dart > 600 LOC AND > 800 LOC: do Task 24a (split _ChatRoomContent into its own file).
- If 600 < chat_page.dart ≤ 800: accept as iterative refinement; file a follow-up issue noting "extract _ChatRoomContent in Stage 2 polish PR" and proceed.

- [ ] **Step 2 (conditional, Task 24a): Extract _ChatRoomContent**

Create `frontend/lib/features/chat/widgets/chat_room_content.dart`:
```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
// ... all imports it currently uses

class ChatRoomContent extends ConsumerStatefulWidget {
  final String roomId;
  // ... existing constructor params

  const ChatRoomContent({super.key, required this.roomId, /* ... */});

  @override
  ConsumerState<ChatRoomContent> createState() => ChatRoomContentState();
}

class ChatRoomContentState extends ConsumerState<ChatRoomContent> {
  // Paste lines 988-1463 body verbatim
}
```

Delete the same lines from `chat_page.dart`, add `import 'widgets/chat_room_content.dart';`, update the call site inside `ChatPage.build()` (was `_ChatRoomContent(...)` → now `ChatRoomContent(...)`).

- [ ] **Step 3: Re-measure**

```bash
wc -l frontend/lib/features/chat/chat_page.dart frontend/lib/features/chat/widgets/chat_room_content.dart
```
Expected: both ≤ 600 LOC.

- [ ] **Step 4: Commit (if conditional split done)**

```bash
git add frontend/lib/features/chat/widgets/chat_room_content.dart frontend/lib/features/chat/chat_page.dart
git commit -m "refactor(frontend): extract ChatRoomContent from chat_page"
```

## Task 25: PR-2B smoke test

- [ ] **Step 1: Local smoke**

Same checklist as Task 13. Pay extra attention to:
- AppBar dropdown menus open/close
- Bookmark sheet shows existing bookmarks
- Room settings dialog saves changes
- Forward dialog suggests target rooms
- In-room search jumps to a match
- Readers sheet shows recent readers of a message
- Invite member modal sends an invite

- [ ] **Step 2: Push**

```bash
git push -u origin refactor/stage-2b-chat-page
```

## Task 26: PR-2B code review + merge

- [ ] **Step 1: Code review**

Same prompt as Task 14, scoped to PR-2B.

- [ ] **Step 2: Merge into develop**

```bash
git checkout develop && git pull --ff-only origin develop
git merge --no-ff refactor/stage-2b-chat-page -m "Merge refactor/stage-2b-chat-page into develop

Stage 2 PR-2B: chat_page.dart 1911 LOC decomposed into 6 dialogs and
6+ widget files. ChatPage shrinks to ~500 LOC, _ChatRoomContent
either stays nested (if ≤ 600) or moves to its own file.

Behavior unchanged. Public route (\`ChatPage\`) and StateNotifier API
preserved.

Confidence: high
Scope-risk: narrow — file moves + public-name renames only."
git push origin develop
```

---

# PR-2C — chat_notifier.dart decomposition

**Goal:** Split ChatNotifier (1076 LOC) responsibilities into helper classes via composition, preserving the `chatNotifierProvider` public API.

**The challenge:** Unlike PR-2A/2B, ChatNotifier is a single `StateNotifier<ChatMessagesState>` and all method calls flow through `state = state.copyWith(...)` on a shared field. Extracted helpers can't own their own state — they need closures over `() => state` (read) and `(newState) => state = newState` (write).

**The pragmatic split** (smaller than the spec's three-facade design — we keep ChatNotifier as the StateNotifier and extract only side-effect-heavy methods that don't touch `state`):

1. Extract `ChatMessagesState` data class into its own file (mechanical).
2. Extract STOMP-handler dispatch (`_onMessage`) into a private helper class that takes a callback for state mutation.
3. Extract `chatNotifierProvider` factory + the auth-watch logic into its own file.

This yields three files all ≤ 600 LOC without refactoring StateNotifier into multiple notifiers (which would break every widget that calls `chatNotifierProvider`).

**Source line map**:

| Lines | Element | Target file |
|-------|---------|-------------|
| 53–138 | `ChatMessagesState` data class | `state/chat_messages_state.dart` |
| 139–end | `ChatNotifier` (937 LOC) | (KEEP HERE, extract _onMessage handler) |
| inside ChatNotifier | `_onMessage(rawMsg)` STOMP dispatch (~110 LOC, multiple branches) | `internal/stomp_message_dispatcher.dart` |

## Task 27: Branch off develop (PR-2C)

- [ ] **Step 1: Branch**

```bash
git checkout develop && git pull --ff-only origin develop
git checkout -b refactor/stage-2c-notifier-facades
```

## Task 28: Extract ChatMessagesState

**Files:**
- Create: `frontend/lib/features/chat/state/chat_messages_state.dart`
- Modify: `frontend/lib/features/chat/chat_notifier.dart`

- [ ] **Step 1: Inspect**

```bash
sed -n '53,138p' frontend/lib/features/chat/chat_notifier.dart
```
Confirm it's a plain data class with `copyWith` + factory + getters.

- [ ] **Step 2: Create the new file**

```dart
import '../../../shared/models/chat_message.dart';
// Add any other type imports the state class uses (MessageDeliveryStatus, etc.)

// Paste lines 53-138 from chat_notifier.dart verbatim.
// No renames needed — ChatMessagesState is already public.
```

- [ ] **Step 3: Delete from chat_notifier.dart + add import**

Delete lines 53-138. Add at the top:
```dart
import 'state/chat_messages_state.dart';
```

- [ ] **Step 4: Verify**

```bash
cd frontend && flutter analyze lib/features/chat/state/chat_messages_state.dart lib/features/chat/chat_notifier.dart 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/state/chat_messages_state.dart frontend/lib/features/chat/chat_notifier.dart
git commit -m "refactor(frontend): extract ChatMessagesState data class"
```

## Task 29: Extract STOMP message dispatcher

**Files:**
- Create: `frontend/lib/features/chat/internal/stomp_message_dispatcher.dart`
- Modify: `frontend/lib/features/chat/chat_notifier.dart`

The `_onMessage(Map<String, dynamic> rawMsg)` method (~110 LOC) is a giant switch over `type` (`MESSAGE_DELETED`, `MESSAGE_EDITED`, `REACTION_UPDATED`, `MESSAGE_PINNED`/`UNPINNED`, plus default new-message append). This logic only reads/writes `state.messages` via state mutation — a perfect extraction candidate.

- [ ] **Step 1: Inspect**

```bash
grep -n "void _onMessage" frontend/lib/features/chat/chat_notifier.dart
```
Note line range. The method ends at its matching closing brace.

- [ ] **Step 2: Create the new file**

```dart
import 'dart:async';
import '../../../core/network/stomp_service.dart';
import '../../../shared/models/chat_message.dart';
import '../state/chat_messages_state.dart';

class StompMessageDispatcher {
  StompMessageDispatcher({
    required this.getCurrentState,
    required this.setState,
    required this.mounted,
    required this.userId,
    required this.currentRoomId,
    required this.computeReadCounts,
    required this.onIncomingChatMessage, // for auto-read-receipt + quick-reply
  });

  final ChatMessagesState Function() getCurrentState;
  final void Function(ChatMessagesState) setState;
  final bool Function() mounted;
  final String userId;
  final String? Function() currentRoomId;
  final Map<String, String> Function(List<ChatMessage>, Map<String, String>) computeReadCounts;
  final void Function(ChatMessage msg) onIncomingChatMessage;

  void dispatch(Map<String, dynamic> rawMsg) {
    if (!mounted()) return;
    final type = rawMsg['type']?.toString().toUpperCase();

    // Paste the body of _onMessage verbatim, with these substitutions:
    //   state           → getCurrentState()
    //   state = state.copyWith(...)  → setState(getCurrentState().copyWith(...))
    //   _userId         → userId
    //   _currentRoomId  → currentRoomId()
    //   _computeReadCounts(...)  → computeReadCounts(...)
    //
    // The default-path tail that does auto-read-receipt + smart-reply
    // schedule (currently lines around 507-529 of chat_notifier.dart)
    // gets called via onIncomingChatMessage(msg) instead of inlined.
  }
}
```

- [ ] **Step 3: Wire into ChatNotifier**

In `ChatNotifier`'s constructor (or right after fields), instantiate:
```dart
late final StompMessageDispatcher _dispatcher = StompMessageDispatcher(
  getCurrentState: () => state,
  setState: (s) => state = s,
  mounted: () => mounted,
  userId: _userId,
  currentRoomId: () => _currentRoomId,
  computeReadCounts: _computeReadCounts,
  onIncomingChatMessage: _afterChatMessageReceived,
);
```

Replace the entire `void _onMessage(Map<String, dynamic> rawMsg)` method body with a single line:
```dart
void _onMessage(Map<String, dynamic> rawMsg) => _dispatcher.dispatch(rawMsg);
```

Add a new private method `_afterChatMessageReceived(ChatMessage msg)` containing the lines that used to follow the default `if (existing.any((m) => m.effectiveId == msg.effectiveId)) return;` block (auto-read-receipt + quick-reply debounce). The dispatcher calls this from its default branch.

- [ ] **Step 4: Verify**

```bash
cd frontend && flutter analyze lib/features/chat/internal/stomp_message_dispatcher.dart lib/features/chat/chat_notifier.dart 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/internal/stomp_message_dispatcher.dart frontend/lib/features/chat/chat_notifier.dart
git commit -m "refactor(frontend): extract StompMessageDispatcher from ChatNotifier"
```

## Task 30: PR-2C size budget audit

- [ ] **Step 1: Measure**

```bash
wc -l frontend/lib/features/chat/chat_notifier.dart \
      frontend/lib/features/chat/state/chat_messages_state.dart \
      frontend/lib/features/chat/internal/stomp_message_dispatcher.dart
```

Expected:
- `chat_notifier.dart`: 1076 - 86 (state) - 110 (dispatcher) = ~880 LOC. Over budget.
- `chat_messages_state.dart`: ~86 LOC. Under.
- `stomp_message_dispatcher.dart`: ~140 LOC (110 dispatch + ~30 boilerplate). Under.

`chat_notifier.dart` is still over 600. Decide:

- If between 600 and 700: accept as iterative refinement, file follow-up.
- If still over 700: do Task 30a.

- [ ] **Step 2 (conditional, Task 30a): Extract read-state cluster**

Identify the read-state methods on ChatNotifier:
- `markRead(roomId, messageId)`
- `updateReadAt(roomId)`
- `_computeReadCounts(messages, readPositions)`
- the read-receipt STOMP handler

Move them to a `frontend/lib/features/chat/internal/read_state_helper.dart`:
```dart
import '../../../core/network/stomp_service.dart';
import '../../../shared/models/chat_message.dart';
import '../state/chat_messages_state.dart';

class ReadStateHelper {
  ReadStateHelper({
    required this.getCurrentState,
    required this.setState,
    required this.stompService,
    required this.userId,
  });

  final ChatMessagesState Function() getCurrentState;
  final void Function(ChatMessagesState) setState;
  final StompService stompService;
  final String userId;

  void markRead(String roomId, String messageId) { /* moved verbatim */ }
  void updateReadAt(String roomId) { /* moved verbatim */ }
  Map<String, String> computeReadCounts(
    List<ChatMessage> messages,
    Map<String, String> readPositions,
  ) { /* moved verbatim */ }
}
```

ChatNotifier instantiates `_readState = ReadStateHelper(...)` in its constructor and delegates each public method:
```dart
void markRead(String roomId, String messageId) =>
    _readState.markRead(roomId, messageId);
```

- [ ] **Step 3: Re-measure**

```bash
wc -l frontend/lib/features/chat/chat_notifier.dart
```
Expected: now ≤ 700 LOC. If still over 700, file an explicit follow-up issue "Stage 2.5: further ChatNotifier split". Don't loop.

- [ ] **Step 4: Commit (if conditional)**

```bash
git add frontend/lib/features/chat/internal/read_state_helper.dart frontend/lib/features/chat/chat_notifier.dart
git commit -m "refactor(frontend): extract ReadStateHelper from ChatNotifier"
```

## Task 31: PR-2C smoke test

- [ ] **Step 1: Local smoke**

Same checklist as Task 13. Pay extra attention to:
- STOMP-driven UI updates: send message from another tab/browser, observe receiving tab updates correctly.
- MESSAGE_DELETED handler: delete a message from another tab, observe tombstone in receiver.
- MESSAGE_EDITED handler: edit from another tab, observe `(수정됨)` appears in receiver.
- REACTION_UPDATED handler: react from another tab, observe emoji aggregation updates.
- Read-receipt: open a room, send a message from another tab, confirm `lastRead` updates.

The dispatcher refactor is the riskiest change in Stage 2 — these smoke checks are non-optional.

- [ ] **Step 2: Push**

```bash
git push -u origin refactor/stage-2c-notifier-facades
```

## Task 32: PR-2C code review + merge

- [ ] **Step 1: Code review**

Prompt:
> Review branch `refactor/stage-2c-notifier-facades` against `develop`.
> Verify:
> 1. `git diff develop..HEAD -- frontend/lib/features/chat/chat_notifier.dart`
>    shows ONLY: import additions, ChatMessagesState removal, _onMessage
>    body collapsed to dispatcher.dispatch(), and (optionally) read-state
>    method bodies replaced by `_readState.X(...)` one-liners.
> 2. `chatNotifierProvider` factory in `chat_provider.dart` is unchanged.
> 3. Public ChatNotifier methods (sendMessage, editMessage, deleteMessage,
>    markRead, joinRoom, leaveRoom, etc.) still exist with identical
>    signatures. Grep the codebase for callers to confirm.
> 4. Every file ≤ 700 LOC (chat_notifier may slightly exceed 600;
>    accept up to 700 with documented follow-up).
> 5. The dispatcher's `setState(getCurrentState().copyWith(...))` pattern
>    correctly replaces every `state = state.copyWith(...)` in the
>    original `_onMessage` body. No reads from a stale captured state.
> 6. `flutter analyze` zero new errors.

- [ ] **Step 2: Address feedback**

- [ ] **Step 3: Merge into develop**

```bash
git checkout develop && git pull --ff-only origin develop
git merge --no-ff refactor/stage-2c-notifier-facades -m "Merge refactor/stage-2c-notifier-facades into develop

Stage 2 PR-2C: chat_notifier.dart 1076 LOC decomposed.
- ChatMessagesState → its own file (state/chat_messages_state.dart)
- _onMessage STOMP dispatch → StompMessageDispatcher (internal/)
- (optional) read-state cluster → ReadStateHelper (internal/)

ChatNotifier remains the single StateNotifier (chatNotifierProvider
unchanged). Helpers receive closures over getCurrentState/setState
to preserve the shared mutable state contract.

Confidence: high for dispatcher, medium for read-state if extracted
(state-mutation closures are easy to get subtly wrong).
Scope-risk: narrow — public API unchanged, internal split only."
git push origin develop
```

---

## Stage 2 close-out

- [ ] All three PRs (2A, 2B, 2C) merged into develop.
- [ ] develop-build.yml green on the latest develop tip.
- [ ] Spec doc `docs/superpowers/specs/2026-05-24-refactor-three-stage-design.md` status updated to "Stage 2 complete; Stage 3 may begin".
- [ ] No file in `frontend/lib/features/chat/` exceeds 700 LOC (target 600, acceptable 700 with documented follow-up issue).
- [ ] Manual smoke test on staging — full checklist green.

Next: spawn writing-plans again for Stage 3 (backend design patterns: @RequireAuth interceptor, Result<T,E> across non-CRUD services, UserPresenceService split).
