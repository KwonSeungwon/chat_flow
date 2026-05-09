# Reply Thread View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users see all replies to a parent message in one place. Today we have one-directional reply navigation (`isReply` → scroll to parent), but no parent-side "view replies" affordance.

**Architecture:**
- **Backend** exposes the existing `findByParentMessageIdOrderByTimestampAsc` JPA query through a new GET endpoint. Build a thin controller method on the existing `MessageInteractionController`. No DTO changes needed.
- **Frontend** computes `replyCount` per parent from the loaded `state.messages` list (no backend dependency). When count > 0, a small "💬 N개 답글" chip renders below the parent bubble. Tap opens a modal bottom sheet with the parent message at top, the full reply list (server-fetched), and a `ChatInput` pre-set with `replyTarget = parent`. New replies arrive via existing STOMP → chat_notifier → state.messages → Consumer rebuilds the panel.
- **Decision recorded:** No `replyCount` field on the DTO. The Consumer-derived count from local messages handles the common case (recent messages, all visible). The thread panel fetches the authoritative reply list from the backend on open, so the "open" experience is always complete even if the local count was stale.

**Tech Stack:**
- Backend: Spring Boot 3.2 + JPA + Mockito/MockMvc.
- Frontend: Flutter 3.22 + Riverpod 2.5 + Dio.

---

## File Structure

**Backend:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java` (add new GET method)
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageReactionService.java` — actually NO. Reply listing is a read; create a small dedicated service to keep boundaries clean.
- Create: `chat-service/src/main/java/com/chatflow/chat/service/MessageThreadService.java` (read-only — fetches reply chain by parent)
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MessageThreadServiceTest.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/MessageInteractionControllerThreadTest.java` (focused on the new endpoint)

**Frontend:**
- Modify: `frontend/lib/features/chat/chat_notifier.dart` — add `replyCountFor(parentId)` derived helper (already cheap from `state.messages`)
- Create: `frontend/lib/features/chat/widgets/thread_panel.dart` — modal bottom sheet
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart` — add reply-count chip on parent messages, wire tap to open `ThreadPanel`
- Modify: `frontend/lib/features/chat/chat_page.dart` — pass the open-thread callback into the messages list

---

## Task 1: Backend — MessageThreadService + GET replies endpoint

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/service/MessageThreadService.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MessageThreadServiceTest.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/MessageInteractionControllerThreadTest.java`

### Step 1: Write failing service test

Create `MessageThreadServiceTest.java`:

```java
package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageThreadServiceTest {

    @Mock private ChatMessageRepository repo;

    private MessageThreadService service;

    @BeforeEach
    void setUp() {
        service = new MessageThreadService(repo);
    }

    @Test
    void findReplies_returns_dto_list_for_parent() {
        ChatMessageEntity reply = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT)
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .deleted(false)
            .build();
        when(repo.findByParentMessageIdOrderByTimestampAsc("p1"))
            .thenReturn(List.of(reply));

        List<ChatMessage> replies = service.findReplies("p1");

        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).getMessageId()).isEqualTo("r1");
        assertThat(replies.get(0).getParentMessageId()).isEqualTo("p1");
        assertThat(replies.get(0).getContent()).isEqualTo("got it");
    }

    @Test
    void findReplies_filters_deleted() {
        ChatMessageEntity deleted = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT)
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .deleted(true)
            .build();
        when(repo.findByParentMessageIdOrderByTimestampAsc("p1"))
            .thenReturn(List.of(deleted));

        List<ChatMessage> replies = service.findReplies("p1");

        assertThat(replies).isEmpty();
    }

    @Test
    void findReplies_empty_when_no_replies() {
        when(repo.findByParentMessageIdOrderByTimestampAsc("p1"))
            .thenReturn(List.of());

        assertThat(service.findReplies("p1")).isEmpty();
    }
}
```

### Step 2: Run, expect FAIL

`./gradlew :chat-service:test --tests MessageThreadServiceTest`
Expected: COMPILATION ERROR — class doesn't exist.

### Step 3: Create service

Create `chat-service/src/main/java/com/chatflow/chat/service/MessageThreadService.java`:

```java
package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only service for fetching the reply chain of a parent message.
 * The repository query is already in place; this service maps entity → DTO
 * and applies the soft-deleted filter.
 */
@Service
@RequiredArgsConstructor
public class MessageThreadService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<ChatMessage> findReplies(String parentMessageId) {
        return chatMessageRepository
            .findByParentMessageIdOrderByTimestampAsc(parentMessageId)
            .stream()
            .filter(e -> !e.isDeleted())
            .map(this::toDto)
            .toList();
    }

    private ChatMessage toDto(ChatMessageEntity e) {
        return ChatMessage.builder()
            .messageId(e.getMessageId())
            .chatRoomId(e.getChatRoomId())
            .userId(e.getUserId())
            .username(e.getUsername())
            .content(e.getContent())
            .type(e.getType())
            .timestamp(e.getTimestamp())
            .parentMessageId(e.getParentMessageId())
            .parentMessagePreview(e.getParentMessagePreview())
            .isAiGenerated(e.isAiGenerated())
            .build();
    }
}
```

If `ChatMessageEntity` exposes additional fields the DTO carries (e.g., `editedAt`, `reactions`, `priority`), include them in `toDto`. Read the entity file first to confirm.

### Step 4: Run service test, expect PASS

`./gradlew :chat-service:test --tests MessageThreadServiceTest`
Expected: 3/3 PASS.

### Step 5: Write failing controller test

Create `chat-service/src/test/java/com/chatflow/chat/controller/MessageInteractionControllerThreadTest.java`:

```java
package com.chatflow.chat.controller;

import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.LinkPreviewService;
import com.chatflow.chat.service.MessageEditService;
import com.chatflow.chat.service.MessagePinService;
import com.chatflow.chat.service.MessageReactionService;
import com.chatflow.chat.service.MessageThreadService;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MessageInteractionControllerThreadTest {

    private MockMvc mockMvc;

    @Mock private MessageEditService messageEditService;
    @Mock private MessageReactionService messageReactionService;
    @Mock private MessagePinService messagePinService;
    @Mock private LinkPreviewService linkPreviewService;
    @Mock private MessageThreadService messageThreadService;

    @InjectMocks
    private MessageInteractionController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getReplies_returns_list() throws Exception {
        ChatMessage reply = ChatMessage.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT)
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .build();
        when(messageThreadService.findReplies("p1")).thenReturn(List.of(reply));

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].messageId").value("r1"))
            .andExpect(jsonPath("$.data[0].parentMessageId").value("p1"));
    }

    @Test
    void getReplies_empty_returns_empty_list() throws Exception {
        when(messageThreadService.findReplies("p1")).thenReturn(List.of());

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0));
    }
}
```

### Step 6: Run, expect FAIL

`./gradlew :chat-service:test --tests MessageInteractionControllerThreadTest`
Expected: FAIL — endpoint returns 404.

### Step 7: Add controller endpoint

Edit `MessageInteractionController.java`:

1. Add the new dependency to the constructor (Lombok `@RequiredArgsConstructor` does this automatically — just declare the field):
   ```java
   private final MessageThreadService messageThreadService;
   ```
   Place this declaration alongside the existing `private final ...` fields.

2. Add the new GET method anywhere in the class (after the existing `toggleReaction` method is a sensible place):

```java
@GetMapping("/{roomId}/messages/{messageId}/replies")
public ResponseEntity<ApiResponse<List<ChatMessage>>> getReplies(
        @PathVariable String roomId,
        @PathVariable String messageId) {
    return ResponseEntity.ok(ApiResponse.ok(messageThreadService.findReplies(messageId)));
}
```

Add the missing imports:
- `import com.chatflow.chat.service.MessageThreadService;`
- `import com.chatflow.common.dto.ChatMessage;`
- `import java.util.List;`

### Step 8: Run all tests, expect PASS

```bash
./gradlew :chat-service:test --tests MessageInteractionControllerThreadTest
./gradlew :chat-service:test
```
Expected: green for the new tests + full suite still green.

### Step 9: Commit

```bash
git add chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java \
        chat-service/src/main/java/com/chatflow/chat/service/MessageThreadService.java \
        chat-service/src/test/java/com/chatflow/chat/service/MessageThreadServiceTest.java \
        chat-service/src/test/java/com/chatflow/chat/controller/MessageInteractionControllerThreadTest.java
git commit -m "$(cat <<'EOF'
feat(chat-service): GET /api/chat/rooms/{roomId}/messages/{messageId}/replies

Expose the existing findByParentMessageIdOrderByTimestampAsc query as a
read-only endpoint. Dedicated MessageThreadService keeps the read path
separate from the mutating MessageReactionService/MessageEditService.

Soft-deleted replies are filtered. Empty result returns 200 with [].

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 2: Frontend — replyCount derivation + ThreadPanel widget

**Files:**
- Create: `frontend/lib/features/chat/widgets/thread_panel.dart`
- Modify: `frontend/lib/features/chat/chat_notifier.dart` (small addition: a public `replyCountFor(parentId)` getter on `ChatNotifier`)
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart` (reply-count chip + onOpenThread callback)
- Modify: `frontend/lib/features/chat/chat_page.dart` (pass the callback)

### Step 1: Add `replyCountFor` to chat_notifier

Open `frontend/lib/features/chat/chat_notifier.dart`. Find a public method on the `ChatNotifier` class (e.g., `markRoomRead`) and add this method below it:

```dart
/// Number of replies in the currently loaded message buffer for a given
/// parent. Used to render the "💬 N개 답글" chip on parent messages.
/// Approximate — only counts what's loaded; the thread panel fetches the
/// authoritative list from the backend on open.
int replyCountFor(String parentMessageId) {
  if (parentMessageId.isEmpty) return 0;
  return state.messages.where((m) {
    final pid = m.parentMessageId;
    return pid != null && pid == parentMessageId && !m.deleted;
  }).length;
}
```

### Step 2: Create ThreadPanel widget

Create `frontend/lib/features/chat/widgets/thread_panel.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_message.dart';
import '../chat_provider.dart' show chatNotifierProvider;
import 'chat_input.dart';

/// Modal sheet showing all replies to a parent message.
/// Loads authoritative reply list from the backend on open, then keeps
/// itself in sync with the room's STOMP feed via chat_notifier state.
class ThreadPanel extends ConsumerStatefulWidget {
  final String roomId;
  final ChatMessage parent;

  const ThreadPanel({super.key, required this.roomId, required this.parent});

  static Future<void> show(BuildContext context, {
    required String roomId,
    required ChatMessage parent,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => ThreadPanel(roomId: roomId, parent: parent),
    );
  }

  @override
  ConsumerState<ThreadPanel> createState() => _ThreadPanelState();
}

class _ThreadPanelState extends ConsumerState<ThreadPanel> {
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _fetchReplies();
  }

  Future<void> _fetchReplies() async {
    final dio = ref.read(dioClientProvider).dio;
    try {
      final resp = await dio.get(
        '/api/chat/rooms/${widget.roomId}/messages/${widget.parent.effectiveId}/replies',
      );
      final raw = (resp.data is Map) ? resp.data['data'] : resp.data;
      if (raw is List) {
        // Merge into chat_notifier state so the panel + main list stay in sync
        final notifier = ref.read(chatNotifierProvider(widget.roomId).notifier);
        for (final entry in raw) {
          if (entry is Map<String, dynamic>) {
            notifier.mergeMessage(ChatMessage.fromJson(entry));
          }
        }
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final messages = ref.watch(chatNotifierProvider(widget.roomId)).messages;
    final replies = messages
        .where((m) =>
            m.parentMessageId == widget.parent.effectiveId && !m.deleted)
        .toList();

    return DraggableScrollableSheet(
      initialChildSize: 0.85,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      expand: false,
      builder: (_, scrollCtrl) {
        return Column(
          children: [
            // Drag handle
            Container(
              width: 40,
              height: 4,
              margin: const EdgeInsets.symmetric(vertical: 8),
              decoration: BoxDecoration(
                color: cs.outline.withAlpha(80),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            // Title
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Row(
                children: [
                  Icon(Icons.forum_outlined, size: 18, color: cs.primary),
                  const SizedBox(width: 8),
                  Text('답글 (${replies.length})',
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w700)),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            // Body
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Text('답글 불러오기 실패: $_error'),
                          ),
                        )
                      : ListView(
                          controller: scrollCtrl,
                          padding: const EdgeInsets.all(12),
                          children: [
                            _ParentSummary(parent: widget.parent),
                            const SizedBox(height: 12),
                            if (replies.isEmpty)
                              Padding(
                                padding: const EdgeInsets.symmetric(vertical: 24),
                                child: Center(
                                  child: Text('아직 답글이 없습니다',
                                      style: TextStyle(color: cs.onSurfaceVariant)),
                                ),
                              )
                            else
                              ...replies.map((r) => _ReplyTile(msg: r)),
                          ],
                        ),
            ),
            const Divider(height: 1),
            // Input — pre-set replyTarget = parent
            Padding(
              padding: EdgeInsets.only(
                bottom: MediaQuery.of(context).viewInsets.bottom,
              ),
              child: ChatInput(
                roomId: widget.roomId,
                replyTarget: widget.parent,
                onCancelReply: () => Navigator.of(context).pop(),
                onSend: (content, {String priority = 'ROUTINE'}) {
                  ref
                      .read(chatNotifierProvider(widget.roomId).notifier)
                      .sendMessage(
                          roomId: widget.roomId,
                          content: content,
                          priority: priority);
                },
                isConnected: true,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _ParentSummary extends StatelessWidget {
  final ChatMessage parent;
  const _ParentSummary({required this.parent});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.surfaceContainer,
        borderRadius: BorderRadius.circular(12),
        border: Border(left: BorderSide(color: cs.primary, width: 3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(parent.username,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: cs.primary)),
          const SizedBox(height: 4),
          Text(parent.content,
              maxLines: 4, overflow: TextOverflow.ellipsis),
        ],
      ),
    );
  }
}

class _ReplyTile extends StatelessWidget {
  final ChatMessage msg;
  const _ReplyTile({required this.msg});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(msg.username,
                  style: const TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w700)),
              const SizedBox(width: 8),
              Text(_fmtTime(msg.timestamp),
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
            ],
          ),
          const SizedBox(height: 2),
          Text(msg.content),
        ],
      ),
    );
  }

  String _fmtTime(String iso) {
    try {
      final dt = DateTime.parse(iso).toLocal();
      return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return '';
    }
  }
}
```

If `ChatInput` doesn't expose all of those parameters with those names (`replyTarget`, `onCancelReply`, `roomId`, `isConnected`, `onSend`), match the actual `ChatInput` signature — read it first. Drop any parameters the widget doesn't have. The minimum viable version: `ChatInput(roomId: ..., replyTarget: parent, onCancelReply: () => Navigator.pop(context), onSend: (content) => sendMessage(...))`.

### Step 3: Add `mergeMessage` helper to chat_notifier

ThreadPanel calls `notifier.mergeMessage(ChatMessage)` — implement it on `ChatNotifier`:

```dart
/// Inserts a message into state if not already present (by effectiveId).
/// Used by ThreadPanel to seed state with server-fetched replies that
/// may not have arrived via STOMP yet (e.g., older replies).
void mergeMessage(ChatMessage msg) {
  final existing = state.messages;
  if (existing.any((m) => m.effectiveId == msg.effectiveId)) return;
  final updated = [...existing, msg]
    ..sort((a, b) => a.timestamp.compareTo(b.timestamp));
  state = state.copyWith(messages: updated);
}
```

Place this method near `_onMessage` (the WebSocket handler) — they share the dedup logic.

### Step 4: Wire reply-count chip in chat_messages_list

Open `frontend/lib/features/chat/widgets/chat_messages_list.dart`. Find the message bubble class (search for `widget.msg.isReply` to locate the bubble's render — there are two such locations, one for own / one for others, around lines 1578 and 1670).

Below each bubble's content (after the bubble closes, before the reaction chips), add a small chip that opens the thread panel when the message has replies. The chip must be wired by the parent passing in a callback. First add the prop on the bubble class:

Search for `final VoidCallback? onScrollToParent;` (line 986 area) and add nearby:

```dart
final void Function(ChatMessage parent)? onOpenThread;
final int replyCount;
```

Update the bubble constructor accordingly. Default `replyCount = 0` so callers can omit it.

Then in the bubble's render, after the message content (before reaction chips), add:

```dart
if (replyCount > 0 && onOpenThread != null)
  Padding(
    padding: const EdgeInsets.only(top: 4),
    child: GestureDetector(
      onTap: () => onOpenThread!(widget.msg),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer.withAlpha(60),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
              color: Theme.of(context).colorScheme.primary.withAlpha(80),
              width: 1),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.forum_outlined,
                size: 13, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 4),
            Text('$replyCount개 답글',
                style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.primary)),
          ],
        ),
      ),
    ),
  ),
```

Place this after the bubble's main `Text(content)` and before the reaction chip row (search for `// Reaction chips inside bubble area` — currently around line 1744).

Then in the parent (the `chat_messages_list` body where bubbles are constructed, around lines 446–550), thread the props through:

```dart
onOpenThread: widget.onOpenThread,
replyCount: widget.replyCountFor(msg.effectiveId),
```

Add the corresponding props on the outer `ChatMessagesList` widget:

```dart
final void Function(ChatMessage parent)? onOpenThread;
final int Function(String parentMessageId) replyCountFor;  // required
```

Default `replyCountFor` to `(_) => 0` if you prefer optional, but the chip only renders for `replyCount > 0`, so an always-zero default is fine for non-thread callers.

### Step 5: Wire from chat_page

Open `frontend/lib/features/chat/chat_page.dart`. Find the `ChatMessagesList(...)` invocation. Add:

```dart
onOpenThread: (parent) => ThreadPanel.show(
  context,
  roomId: widget.roomId,
  parent: parent,
),
replyCountFor: (id) =>
    ref.read(chatNotifierProvider(widget.roomId).notifier).replyCountFor(id),
```

Add `import 'widgets/thread_panel.dart';` at the top.

### Step 6: flutter analyze

```bash
cd frontend && flutter analyze \
  lib/features/chat/widgets/thread_panel.dart \
  lib/features/chat/widgets/chat_messages_list.dart \
  lib/features/chat/chat_notifier.dart \
  lib/features/chat/chat_page.dart 2>&1 | tail -10
```
Expected: 0 errors. Pre-existing infos OK.

### Step 7: Smoke build

```bash
cd frontend && flutter build web --release 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL.

### Step 8: Commit

```bash
git add frontend/lib/features/chat/widgets/thread_panel.dart \
        frontend/lib/features/chat/widgets/chat_messages_list.dart \
        frontend/lib/features/chat/chat_notifier.dart \
        frontend/lib/features/chat/chat_page.dart
git commit -m "$(cat <<'EOF'
feat(frontend): reply thread panel — view all replies to a parent message

Adds a small "💬 N개 답글" chip on parent messages whose replies are
visible in the loaded buffer. Tap opens a modal bottom sheet that:

- fetches the authoritative reply list from the new backend endpoint
- merges fetched replies into chat_notifier state (so the main list and
  the panel stay in sync)
- shows the parent message header + reply list
- embeds ChatInput pre-set with replyTarget = parent so users can reply
  inline without leaving the panel

replyCountFor() is derived from local state.messages — approximate but
fast. The thread-panel fetch closes any gap on open.

Confidence: medium
Scope-risk: narrow
Not-tested: panel rendering on very small screens (<320px width)
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- Backend endpoint → Task 1.
- Reply count chip → Task 2 Step 4.
- Thread panel → Task 2 Step 2.
- Reply-from-thread → Task 2 Step 2 ChatInput integration.

**Placeholder scan:** every step has runnable commands or full code. Where the existing widget API is referenced (e.g., `ChatInput(...)` signature), the plan instructs the implementer to verify and adapt.

**Type consistency:**
- `replyCountFor(String)` — defined in Task 2 Step 1, called in Task 2 Steps 4–5.
- `mergeMessage(ChatMessage)` — defined in Task 2 Step 3, called in Step 2.
- `MessageThreadService.findReplies(String)` — defined in Task 1 Step 3, called in Step 7 (controller) and tested in Step 5.
- `ApiResponse.ok(List<ChatMessage>)` — controller returns this; frontend unwraps via `resp.data['data']`.

---

## Execution

Use `superpowers:subagent-driven-development`. Branch already created: `feature/thread-view`.

Tasks 1 and 2 are tightly coupled (frontend calls backend endpoint), but Task 1 produces a stable API contract before Task 2 needs it. Sequential dispatch.
