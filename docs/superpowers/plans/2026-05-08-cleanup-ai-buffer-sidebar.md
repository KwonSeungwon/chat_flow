# Cleanup Cycle (가): AI Buffer Filter + AI Type Whitelist + Sidebar Split

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three small operational fixes:
1. AI summary buffer must only contain CHAT messages (today it accepts JOIN/LEAVE/SYSTEM, polluting the 10-msg threshold).
2. `MessageSenderService.shouldRequestAISummary` switches from blacklist (excludes FILE) to whitelist (only CHAT).
3. Split the 1271-line `chat_room_sidebar.dart` into focused widgets — extract `SidebarHeader` and `RoomTile` to their own files.

**Architecture:**
- Tasks 1+2 are pure backend type-filter additions. Each is a one-line behavior change with a unit test.
- Task 3 is a mechanical move-and-rename refactor: drop the `_` prefix from the two private widgets, move to new files, update imports. No behavior change. Verified by `flutter analyze`.

**Tech Stack:** Spring Boot 3.2 (backend), Mockito + JUnit 5; Flutter 3.22 (frontend).

---

## File Structure

**Backend:**
- Modify: `ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java`
- Test (existing): `ai-summary-service/src/test/java/com/chatflow/aisummary/service/AiSummaryServiceTest.java` — add cases
- Test (existing): `chat-service/src/test/java/com/chatflow/chat/service/MessageSenderServiceTest.java` — add cases (or create if missing)

**Frontend:**
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart` (move `_SidebarHeader` and `_RoomTile` out, fix imports)
- Create: `frontend/lib/features/chat/widgets/sidebar_header.dart`
- Create: `frontend/lib/features/chat/widgets/room_tile.dart`

---

## Task 1: Filter AI summary buffer to CHAT type only

**Files:**
- Modify: `ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java` (`addMessageAndCheckTrigger`)
- Test: `ai-summary-service/src/test/java/com/chatflow/aisummary/service/AiSummaryServiceTest.java`

**Why:** `handleChatMessage` listens to the `chat-messages` Kafka topic which contains every message type produced by chat-service (CHAT, JOIN, LEAVE, SYSTEM, FILE, AI_SUMMARY-rebroadcasts won't reach here but JOIN/LEAVE absolutely do). The buffer counts toward the 10-message summary trigger, so a room with active joins/leaves can trip the trigger with only 5–6 actual user messages.

- [ ] **Step 1: Add a failing test for the type filter**

Open `ai-summary-service/src/test/java/com/chatflow/aisummary/service/AiSummaryServiceTest.java`. Add (do not replace existing tests):

```java
@Test
void addMessage_skips_non_chat_types() {
    String roomId = "room-filter";
    String bufferKey = "chatflow:summary:buffer:" + roomId;

    ChatMessage join = ChatMessage.builder()
        .chatRoomId(roomId).userId("u1").username("alice")
        .content("alice joined").type(ChatMessage.MessageType.JOIN)
        .timestamp(java.time.LocalDateTime.now())
        .build();

    service.handleChatMessage(toJson(join));

    // Buffer should remain empty — JOIN must not be stored.
    verify(redisTemplate.opsForList(), never())
        .rightPush(eq(bufferKey), anyString());
}
```

If `toJson` helper is not present in the test class, add it inside the class:

```java
private String toJson(ChatMessage m) throws Exception {
    return objectMapper.writeValueAsString(m);
}
```

- [ ] **Step 2: Run the test, expect FAIL**

`./gradlew :ai-summary-service:test --tests AiSummaryServiceTest.addMessage_skips_non_chat_types`
Expected: FAIL — `rightPush` was called once.

- [ ] **Step 3: Add the type filter to `addMessageAndCheckTrigger`**

Edit `AiSummaryService.java`. At the top of `addMessageAndCheckTrigger(ChatMessage message)`, before any Redis call, add:

```java
private void addMessageAndCheckTrigger(ChatMessage message) {
    // Only real user CHAT messages contribute to the summary buffer.
    // JOIN/LEAVE/SYSTEM/FILE/AI_SUMMARY would inflate the 10-msg trigger
    // and pollute the summary content.
    if (message.getType() != ChatMessage.MessageType.CHAT) {
        return;
    }

    String roomId = message.getChatRoomId();
    // ... rest unchanged ...
```

- [ ] **Step 4: Run the test, expect PASS**

`./gradlew :ai-summary-service:test --tests AiSummaryServiceTest`
Expected: all green (existing tests + new filter test).

- [ ] **Step 5: Commit**

```bash
git add ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java \
        ai-summary-service/src/test/java/com/chatflow/aisummary/service/AiSummaryServiceTest.java
git commit -m "$(cat <<'EOF'
fix(ai-summary): only CHAT messages enter the summary buffer

JOIN/LEAVE/SYSTEM/FILE were inflating the 10-message threshold and
contaminating the summary prompt. Filter at the buffer entry point so
both the trigger count and the conversation snapshot reflect actual
user dialogue.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 2: shouldRequestAISummary becomes an explicit CHAT whitelist

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java`
- Test: `chat-service/src/test/java/com/chatflow/chat/service/MessageSenderServiceTest.java` (create if missing)

**Why:** Today the method only excludes `FILE`. SYSTEM and JOIN/LEAVE messages with content > 100 chars would request a summary — they shouldn't. Switching to a positive `CHAT`-only whitelist makes the behavior obvious and resilient to new MessageType values.

- [ ] **Step 1: Inspect the existing test**

Run: `find chat-service/src/test -name "MessageSenderServiceTest.java"`
- If found, open it and add new test cases below.
- If not found, create it (template in Step 2).

- [ ] **Step 2: Add failing tests for the whitelist**

Add these tests to `MessageSenderServiceTest.java`:

```java
@Test
void shouldRequestAISummary_returns_false_for_non_chat_types() {
    String longContent = "x".repeat(150);
    for (ChatMessage.MessageType t : ChatMessage.MessageType.values()) {
        if (t == ChatMessage.MessageType.CHAT) continue;
        ChatMessage msg = ChatMessage.builder()
            .type(t).content(longContent).build();
        // Use reflection or expose package-private — see helper
        assertThat(service.shouldRequestAISummaryForTest(msg))
            .as("type=%s should not request summary", t)
            .isFalse();
    }
}

@Test
void shouldRequestAISummary_returns_true_only_for_long_chat() {
    ChatMessage shortChat = ChatMessage.builder()
        .type(ChatMessage.MessageType.CHAT).content("short").build();
    assertThat(service.shouldRequestAISummaryForTest(shortChat)).isFalse();

    ChatMessage longChat = ChatMessage.builder()
        .type(ChatMessage.MessageType.CHAT).content("x".repeat(150)).build();
    assertThat(service.shouldRequestAISummaryForTest(longChat)).isTrue();
}
```

If `shouldRequestAISummary` is `private`, expose a package-private test seam:

```java
// In MessageSenderService.java — keep the original private method, add a
// package-private accessor used only by tests.
boolean shouldRequestAISummaryForTest(ChatMessage message) {
    return shouldRequestAISummary(message);
}
```

- [ ] **Step 3: Run tests, expect FAIL**

`./gradlew :chat-service:test --tests MessageSenderServiceTest`
Expected: FAIL — current logic returns true for `JOIN` with long content (or test seam method doesn't exist).

- [ ] **Step 4: Convert to whitelist**

Edit `MessageSenderService.java`:

```java
private boolean shouldRequestAISummary(ChatMessage message) {
    if (message.getType() != MessageType.CHAT) return false;
    return message.getContent() != null && message.getContent().length() > 100;
}
```

- [ ] **Step 5: Run tests, expect PASS**

`./gradlew :chat-service:test --tests MessageSenderServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java \
        chat-service/src/test/java/com/chatflow/chat/service/MessageSenderServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(chat-service): shouldRequestAISummary becomes CHAT-whitelist

Previously the method only excluded FILE. SYSTEM/JOIN/LEAVE with content
> 100 chars would have requested an AI summary. Switch to an explicit
"CHAT only" whitelist — defensive, and obvious to read.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 3: Split chat_room_sidebar.dart — extract SidebarHeader and RoomTile

**Files:**
- Create: `frontend/lib/features/chat/widgets/sidebar_header.dart`
- Create: `frontend/lib/features/chat/widgets/room_tile.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`

**Why:** The file is 1271 lines and growing every cycle. Extracting the two largest standalone widgets reduces the main file by ~600 lines and makes future edits less error-prone. The error/empty placeholders (`_ErrorState`, `_EmptyRoomState`) stay in the main file because they're tiny and tightly coupled to the room-list state.

**Mechanical move — no behavior change. Verified by `flutter analyze` only.**

- [ ] **Step 1: Map current ranges**

```bash
grep -n "^class _" frontend/lib/features/chat/widgets/chat_room_sidebar.dart
```
Expected output:
```
599:class _SidebarHeader extends StatelessWidget {
777:class _RoomTile extends StatefulWidget {
812:class _RoomTileState extends State<_RoomTile> {
1184:class _ErrorState extends StatelessWidget {
1220:class _EmptyRoomState extends StatelessWidget {
```

- [ ] **Step 2: Create `sidebar_header.dart`**

```bash
touch frontend/lib/features/chat/widgets/sidebar_header.dart
```

Move the entire `_SidebarHeader` class block (lines 596–795 inclusive — verify by reading the file; the block runs from the section divider comment to before the next `// ────…` divider) into `sidebar_header.dart`. Rename the class to `SidebarHeader` (drop the `_`). Add the file header:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../chat_provider.dart' show RoomSortOption;
import '../mentions_provider.dart';

// Sidebar header — brand row + sort + "more" popup + new-room button.
class SidebarHeader extends StatelessWidget {
  final VoidCallback onCreateTap;
  final VoidCallback? onDmTap;
  final VoidCallback? onRefresh;
  final void Function(RoomSortOption)? onSortSelected;
  final RoomSortOption currentSort;
  const SidebarHeader({
    super.key,
    required this.onCreateTap,
    this.onDmTap,
    this.onRefresh,
    this.onSortSelected,
    this.currentSort = RoomSortOption.recent,
  });

  // ... body, identical to the moved code ...
}
```

Drop the `const _SidebarHeader(...)` constructor's `_` and the `// ──…` divider comments. Keep the `_sortItem(...)` helper as a private method on the class (single underscore prefix on methods is fine inside a public class).

- [ ] **Step 3: Create `room_tile.dart`**

```bash
touch frontend/lib/features/chat/widgets/room_tile.dart
```

Move both `_RoomTile` (lines 777–810) and `_RoomTileState` (lines 812–~1180) into `room_tile.dart`. Rename to `RoomTile` and `RoomTileState` (drop `_`). Add the file header with the imports the moved code needs (Material, Riverpod, model imports for `ChatRoom`, `NotificationPolicy`, theme constants). Use `ConsumerState` if the state class needs `ref`; check whether the original used `State` or `ConsumerState`.

For Step 2/3 import discovery: at the bottom of the original `chat_room_sidebar.dart`, look at what `_SidebarHeader` and `_RoomTile` reference (e.g., `AppColors`, `ChatRoom`, `NotificationPolicy`, providers). Bring exactly those imports to the new files. Don't bring imports the moved code doesn't use.

- [ ] **Step 4: Update the main file**

Edit `chat_room_sidebar.dart`:

1. Remove the `_SidebarHeader` class body (now in `sidebar_header.dart`).
2. Remove the `_RoomTile` and `_RoomTileState` class bodies (now in `room_tile.dart`).
3. Add imports near the top:
   ```dart
   import 'sidebar_header.dart';
   import 'room_tile.dart';
   ```
4. Replace any references inside `_ChatRoomSidebarState.build`:
   - `_SidebarHeader(...)` → `SidebarHeader(...)`
   - `_RoomTile(...)` → `RoomTile(...)`
5. Keep `_ErrorState` and `_EmptyRoomState` in this file (small, tightly coupled).
6. Remove the section-divider comments that referred only to the moved widgets.

- [ ] **Step 5: Verify with analyzer**

```bash
cd frontend && flutter analyze lib/features/chat/widgets/sidebar_header.dart lib/features/chat/widgets/room_tile.dart lib/features/chat/widgets/chat_room_sidebar.dart
```
Expected: 0 errors. Pre-existing `info`-level deprecation warnings on `Radio.groupValue` / `onChanged` (line 833-834 in the OLD numbering) will follow `_RoomTileState` into `room_tile.dart` — that's expected.

- [ ] **Step 6: Verify line counts**

```bash
wc -l frontend/lib/features/chat/widgets/chat_room_sidebar.dart \
      frontend/lib/features/chat/widgets/sidebar_header.dart \
      frontend/lib/features/chat/widgets/room_tile.dart
```
Expected: main file shrinks to ≤700 lines; `sidebar_header.dart` ~200 lines; `room_tile.dart` ~400 lines.

- [ ] **Step 7: Smoke build**

```bash
cd frontend && flutter build web --release 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL. (No need to deploy — the deploy step at the end of the cycle covers this.)

- [ ] **Step 8: Commit**

```bash
git add frontend/lib/features/chat/widgets/sidebar_header.dart \
        frontend/lib/features/chat/widgets/room_tile.dart \
        frontend/lib/features/chat/widgets/chat_room_sidebar.dart
git commit -m "$(cat <<'EOF'
refactor(frontend): extract SidebarHeader and RoomTile into their own files

chat_room_sidebar.dart had grown to 1271 lines, mixing the entry-point
ConsumerStatefulWidget with two large self-contained child widgets.
Move SidebarHeader and RoomTile (with its State class) to their own
files so each unit fits in context. _ErrorState and _EmptyRoomState
remain in the main file because they are tiny and tied to the room-list
state.

No behavior change. flutter analyze + flutter build web both clean.

Confidence: high
Scope-risk: narrow
Directive: keep widget files <500 lines — split when growing past that
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- Item 1 (AI buffer filter) → Task 1.
- Item 2 (whitelist) → Task 2.
- Item 3 (sidebar split) → Task 3.

**Placeholder scan:** every step has runnable commands or full code snippets.

**Type consistency:**
- `SidebarHeader` (Task 3 Step 2) and `RoomTile` (Step 3) match the references inside the main file (Step 4).
- `MessageType.CHAT` is the canonical enum value, used identically in Tasks 1 and 2.

---

## Execution

Use `superpowers:subagent-driven-development` against this plan file. Branch already created: `feature/cleanup-ai-buffer-sidebar-split`.

Tasks 1 and 2 are independent. Task 3 has no backend coupling. Sequential dispatch is safe.
