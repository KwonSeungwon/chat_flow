# QA Bug-Fix Cycle (2026-05-10)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bugs surfaced by the QA pass on the recently deployed thread view + cleanup cycle. Six small, focused fixes — no new features.

**Architecture:** Tasks are independent and isolated. Each touches 1–2 files. Sequential dispatch.

**Tech Stack:** Spring Boot 3.2 (backend), Flutter 3.22 + Riverpod (frontend).

---

## File Structure

**Backend:**
- Modify: `ai-summary-service/.../AiSummaryService.java` (drop duplicate listener)
- Modify: `chat-service/.../ChatRoomController.java` (REST send: pass parentMessageId, priority, fileFields)
- Modify: `chat-service/.../MessageInteractionController.java` (replies endpoint: membership check + roomId scope)
- Modify: `chat-service/.../MessageThreadService.java` (accept roomId for scoping)
- Modify: `chat-service/.../repository/ChatMessageRepository.java` (new query with chatRoomId filter)
- Modify tests for each.

**Frontend:**
- Modify: `frontend/lib/features/chat/chat_notifier.dart` (`retryFailedMessage` preserve parentMessageId; offline queue strip `_localId`)
- Modify: `frontend/lib/features/auth/auth_provider.dart` (call `WebUnloadHandler.unregister()` on logout)
- Modify: `frontend/lib/features/chat/widgets/thread_panel.dart` (watch parent for deletion)
- Modify: `frontend/lib/features/chat/widgets/chat_messages_list.dart` (`_FileBubble` accepts `onOpenThread`/`replyCount`)

---

## Task 1: Backend — Drop duplicate `AI_SUMMARY_REQUESTS` Kafka listener

**File:** `ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java`

**Why:** `MessageSenderService` publishes long CHAT messages to BOTH `chat-messages` and `ai-summary-requests` topics. `AiSummaryService` has `@KafkaListener` on both, and both call `addMessageAndCheckTrigger`. Net effect: every long CHAT message gets buffered TWICE in Redis, so the 10-message trigger fires after only 5 real messages.

`AiSummaryController` (`/api/ai-summary/ask`, `/shift-report`, `/quick-replies`) calls service methods directly — it does not publish to the topic. The `ai-summary-requests` topic has only one path (from MessageSenderService) and that path is already covered by the CHAT_MESSAGES listener. The simplest fix is to drop the duplicate listener; the CHAT_MESSAGES listener already buffers every CHAT message.

- [ ] **Step 1: Remove the duplicate listener method**

Delete the entire `handleSummaryRequest(String messageJson)` method (annotated `@KafkaListener(topics = KafkaTopics.AI_SUMMARY_REQUESTS)`) from `AiSummaryService.java`.

- [ ] **Step 2: Verify no test depended on it**

```bash
grep -rn "handleSummaryRequest\|AI_SUMMARY_REQUESTS" ai-summary-service/src/test/ chat-service/src/test/
```
If any test references the deleted method, update or remove that test (likely none — but check).

- [ ] **Step 3: Run AI summary tests**

`./gradlew :ai-summary-service:test`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 4: Commit**

```bash
git add ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java
git commit -m "$(cat <<'EOF'
fix(ai-summary): drop duplicate AI_SUMMARY_REQUESTS Kafka listener

MessageSenderService publishes long CHAT messages to both chat-messages
and ai-summary-requests. AiSummaryService listened on both topics, both
calling addMessageAndCheckTrigger, so every long message was buffered
twice in Redis. The 10-message threshold thus fired after only 5 real
messages and the summary prompt contained duplicates.

The chat-messages listener already covers every CHAT message; the
ai-summary-requests path was redundant. AiSummaryController calls
service methods directly, so removing this listener does not break any
caller.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 2: Backend — REST send must honor parentMessageId, priority, and file fields

**File:** `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java` (around line 365)

**Why:** The REST `POST /api/chat/rooms/{roomId}/messages` endpoint currently reads only `content` and `forwardedFrom` from the body, hard-codes `priority = ROUTINE`, and silently drops `parentMessageId`, `fileUrl`, `fileName`, `fileContentType`. Replies posted via this REST path become top-level messages; handoff-room priority flags are lost; file uploads can't be re-sent on retry.

- [ ] **Step 1: Read current handler**

Read lines 365–393 to confirm the body parsing.

- [ ] **Step 2: Extend the body parsing**

Replace the existing `sendMessage` method body. Keep auth + content validation, but parse all relevant fields:

```java
@PostMapping("/{roomId}/messages")
public ResponseEntity<ApiResponse<Void>> sendMessage(
        @PathVariable String roomId,
        @RequestBody Map<String, String> body,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Username", required = false) String username) {
    if (userId == null || userId.isBlank()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("인증이 필요합니다."));
    }
    String content = body.get("content");
    if (content == null || content.isBlank()) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("content가 필요합니다."));
    }
    ChatMessage msg = new ChatMessage();
    msg.setChatRoomId(roomId);
    msg.setUserId(userId);
    msg.setUsername(username != null ? username : userId);
    msg.setContent(content);
    msg.setType(ChatMessage.MessageType.CHAT);
    String priority = body.get("priority");
    msg.setPriority(priority != null && !priority.isBlank() ? priority : "ROUTINE");
    String parentMessageId = body.get("parentMessageId");
    if (parentMessageId != null && !parentMessageId.isBlank()) {
        msg.setParentMessageId(parentMessageId);
    }
    String forwardedFrom = body.get("forwardedFrom");
    if (forwardedFrom != null && !forwardedFrom.isBlank()) {
        msg.setForwardedFrom(forwardedFrom);
    }
    String fileUrl = body.get("fileUrl");
    String fileName = body.get("fileName");
    String fileContentType = body.get("fileContentType");
    if (fileUrl != null && !fileUrl.isBlank()) {
        msg.setFileUrl(fileUrl);
        msg.setFileName(fileName);
        msg.setFileContentType(fileContentType);
    }
    messageSenderService.send(msg);
    return ResponseEntity.ok(ApiResponse.ok(null, "메시지를 전송했습니다."));
}
```

`MessageSenderService.send()` already populates `parentMessagePreview` from `parentMessageId` (lines 74–93 of that file) — we just have to pass the field through.

- [ ] **Step 3: Add or extend test**

Find or create a test for `ChatRoomController.sendMessage`. Add cases:
- POST with `parentMessageId` set — verify `messageSenderService.send` is called with a `ChatMessage` whose `parentMessageId` matches
- POST with `priority: "STAT"` — verify priority is propagated

If `ChatRoomControllerTest` exists, append. If not, create a focused new test class using `standaloneSetup` + `GlobalExceptionHandler`.

- [ ] **Step 4: Run tests**

`./gradlew :chat-service:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java \
        chat-service/src/test/java/com/chatflow/chat/controller/
git commit -m "$(cat <<'EOF'
fix(chat-service): REST send honors parentMessageId, priority, file fields

The REST POST /api/chat/rooms/{roomId}/messages endpoint dropped
parentMessageId, hard-coded priority=ROUTINE, and ignored file fields.
Replies posted via REST became top-level messages, handoff priority
was lost, and file retries were impossible.

STOMP send already passes these fields. The REST path now matches.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 3: Backend — Replies endpoint membership check + chatRoomId scope

**Files:**
- `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`
- `chat-service/src/main/java/com/chatflow/chat/service/MessageThreadService.java`
- `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java`
- `chat-service/src/test/java/com/chatflow/chat/service/MessageThreadServiceTest.java`
- `chat-service/src/test/java/com/chatflow/chat/controller/MessageInteractionControllerThreadTest.java`

**Why:** The current GET `/api/chat/rooms/{roomId}/messages/{messageId}/replies` ignores `roomId` and skips authentication membership check. Any authenticated user who knows or guesses a `messageId` can read all its replies regardless of which room they belong to. Two gaps:
1. No `X-User-Id` header reading + `existsByRoomIdAndUserId` membership check
2. The repo query filters only by `parentMessageId`, not by `chatRoomId` — so a `messageId` from another room would still resolve

Fix both: scope the JPA query to `chatRoomId` AND check membership at the controller.

- [ ] **Step 1: Add the chatRoomId-scoped repo query**

Edit `ChatMessageRepository.java`. Add below the existing reply queries:

```java
List<ChatMessageEntity>
    findByChatRoomIdAndParentMessageIdAndDeletedFalseOrderByTimestampAsc(
        String chatRoomId, String parentMessageId);
```

- [ ] **Step 2: Update MessageThreadService to accept chatRoomId**

Edit `MessageThreadService.java`:

```java
@Transactional(readOnly = true)
public List<ChatMessageEntity> findReplies(String chatRoomId, String parentMessageId) {
    return chatMessageRepository
        .findByChatRoomIdAndParentMessageIdAndDeletedFalseOrderByTimestampAsc(
            chatRoomId, parentMessageId);
}
```

- [ ] **Step 3: Update controller — add userId header + membership check**

Edit `MessageInteractionController.java`. Inject `RoomMemberRepository`:

```java
private final RoomMemberRepository roomMemberRepository;
```

Update `getReplies`:

```java
@GetMapping("/{roomId}/messages/{messageId}/replies")
public ResponseEntity<ApiResponse<List<ChatMessageEntity>>> getReplies(
        @PathVariable String roomId,
        @PathVariable String messageId,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
    if (userId == null || userId.isBlank()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("인증이 필요합니다."));
    }
    if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("방 멤버가 아닙니다."));
    }
    return ResponseEntity.ok(
        ApiResponse.ok(messageThreadService.findReplies(roomId, messageId)));
}
```

Add imports as needed (`com.chatflow.chat.repository.RoomMemberRepository`).

- [ ] **Step 4: Update tests**

`MessageThreadServiceTest.java`: change the stub to use the new repo method name + assert `(chatRoomId, parentMessageId)` arguments.

`MessageInteractionControllerThreadTest.java`: add `@Mock private RoomMemberRepository roomMemberRepository;`. Add cases:
- 401 when X-User-Id missing
- 403 when membership check returns false
- 200 when membership returns true (existing test, just add `.header("X-User-Id", "u1")` and stub `existsByRoomIdAndUserId(...).thenReturn(true)`)

- [ ] **Step 5: Run tests**

`./gradlew :chat-service:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Update frontend Dio client to send X-User-Id header**

Check `frontend/lib/core/network/dio_client.dart` for an existing X-User-Id interceptor. The frontend likely already attaches it (search for `X-User-Id`). If yes, no change needed. If not, add it from `authProvider.userId`.

```bash
grep -n "X-User-Id\|userId" frontend/lib/core/network/dio_client.dart
```

- [ ] **Step 7: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java \
        chat-service/src/main/java/com/chatflow/chat/service/MessageThreadService.java \
        chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java \
        chat-service/src/test/java/com/chatflow/chat/service/MessageThreadServiceTest.java \
        chat-service/src/test/java/com/chatflow/chat/controller/MessageInteractionControllerThreadTest.java
git commit -m "$(cat <<'EOF'
fix(chat-service): replies endpoint enforces room membership + scope

GET /api/chat/rooms/{roomId}/messages/{messageId}/replies previously
ignored roomId and accepted any messageId from any authenticated user.

- Add X-User-Id header check (401 if missing)
- Verify roomMemberRepository.existsByRoomIdAndUserId (403 if not member)
- New repo query findByChatRoomIdAndParentMessageIdAndDeletedFalse… so
  the JPA filter scopes the result to the requested room (defense in depth)

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 4: Frontend — `retryFailedMessage` preserves parentMessageId + offline queue strips `_localId`

**File:** `frontend/lib/features/chat/chat_notifier.dart`

**Why:**
- `retryFailedMessage` calls `sendMessage(roomId, content, priority)` without passing `replyOverride`. A failed reply on retry becomes a top-level message — silent data loss.
- The offline queue stores the raw `msg` map including `_localId`. When flushed, `onSend: (msg) => _stompService.sendMessage(msg)` passes `_localId` to STOMP. The backend silently ignores unknown fields (Jackson default), but it's a contract leak.

- [ ] **Step 1: Read both call sites**

```bash
grep -n "retryFailedMessage\|onSend: (msg) =>" frontend/lib/features/chat/chat_notifier.dart
```

- [ ] **Step 2: Fix retryFailedMessage**

In `retryFailedMessage(ChatMessage msg)` (around line 823), find the `sendMessage(...)` call and add the `replyOverride` parameter:

```dart
void retryFailedMessage(ChatMessage msg) {
  // ... existing code that removes the failed message ...
  // The retry is a fresh send, so we look up the parent from current state
  // (it must still exist; if not, we send as top-level — best effort).
  final parent = msg.parentMessageId == null
      ? null
      : state.messages
          .where((m) => m.effectiveId == msg.parentMessageId)
          .firstOrNull;
  sendMessage(
    roomId: state.roomId ?? '',
    content: msg.content,
    priority: msg.priority,
    replyOverride: parent,
  );
}
```

Adjust to match the actual existing code structure — the goal is to pass `replyOverride` so the retry preserves thread association.

- [ ] **Step 3: Fix offline queue flush**

Find the offline queue flush at line ~258:

```dart
onSend: (msg) => _stompService.sendMessage(msg),
```

Change to:

```dart
onSend: (msg) => _stompService.sendMessage(
  Map<String, dynamic>.from(msg)..remove('_localId')),
```

- [ ] **Step 4: flutter analyze**

```bash
cd frontend && flutter analyze lib/features/chat/chat_notifier.dart 2>&1 | tail -5
```
Expected: 0 errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/chat_notifier.dart
git commit -m "$(cat <<'EOF'
fix(frontend): retryFailedMessage preserves thread + offline queue strips _localId

- retryFailedMessage now passes replyOverride so a failed reply
  retried still posts as a thread reply (was silently top-level).
- Offline queue's flush onSend now strips the local-only _localId
  field before sending to STOMP. Backend was silently ignoring it
  but the contract leak is real (and inconsistent with the live path).

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 5: Frontend — `WebUnloadHandler.unregister()` on logout

**File:** `frontend/lib/features/auth/auth_provider.dart`

**Why:** `WebUnloadHandler.register()` is called once at app startup; `unregister()` is never called. After explicit logout, the `beforeunload` listener stays active. When the user closes the tab, the handler tries to fire `POST /api/fcm/unsubscribe-all` with a stale or empty JWT. The intent (per the source comment) was to unregister on logout — the code path was just never wired.

- [ ] **Step 1: Add the call in logout**

Read `auth_provider.dart` around line 181. The `logout()` method calls `FcmService.deleteToken()`. Right after that call, add:

```dart
WebUnloadHandler.unregister();
```

Add the import:

```dart
import '../../core/services/web_unload_handler.dart';
```

- [ ] **Step 2: flutter analyze**

```bash
cd frontend && flutter analyze lib/features/auth/auth_provider.dart 2>&1 | tail -3
```
Expected: 0 errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/lib/features/auth/auth_provider.dart
git commit -m "$(cat <<'EOF'
fix(frontend): unregister beforeunload handler on logout

WebUnloadHandler.unregister() was defined but never called. After
explicit logout the listener stayed active and would fire a spurious
POST /api/fcm/unsubscribe-all with an empty JWT on the next tab close.
The source comment ("Use after explicit logout so the fetch doesn't
fire") documented the intent — the wire was missing.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 6: Frontend — ThreadPanel watches parent + `_FileBubble` supports thread

**Files:**
- `frontend/lib/features/chat/widgets/thread_panel.dart`
- `frontend/lib/features/chat/widgets/chat_messages_list.dart`

**Why:**
- `_ParentSummary` renders `widget.parent.content` (a captured snapshot). If the parent is deleted while the panel is open, the deletion is invisible — stale text persists.
- `_FileBubble` doesn't accept `onOpenThread` / `replyCount`, so a file message that has replies never shows the chip and can't be opened as a thread parent.

- [ ] **Step 1: ThreadPanel — read parent from state**

Edit `thread_panel.dart` `_ThreadPanelState.build`. Replace the captured `widget.parent` reference inside `_ParentSummary` with a lookup in current state. Pseudocode at the top of `build`:

```dart
final messages = ref.watch(chatNotifierProvider(widget.roomId)).messages;
final liveParent = messages
    .where((m) => m.effectiveId == widget.parent.effectiveId)
    .firstOrNull ?? widget.parent;
final replies = messages
    .where((m) =>
        m.parentMessageId == widget.parent.effectiveId && !m.deleted)
    .toList();
```

Then pass `liveParent` instead of `widget.parent` into `_ParentSummary(parent: liveParent)`.

`_ParentSummary` already shows `parent.content` — when `liveParent.deleted` is true and `parent.content` was rewritten to `'삭제된 메시지입니다.'` by the message-deleted broadcast handler, the panel will reflect that automatically.

- [ ] **Step 2: _FileBubble — accept thread props**

Edit `chat_messages_list.dart`. Find the `_FileBubble` class (search `class _FileBubble`). Add fields:

```dart
final void Function(ChatMessage parent)? onOpenThread;
final int replyCount;
```

Update its constructor to take these (default `replyCount = 0`).

In its render (find the bubble body), insert the same chip block used in `_ChatBubble`:

```dart
if (replyCount > 0 && onOpenThread != null)
  Padding(
    padding: const EdgeInsets.only(top: 4),
    child: GestureDetector(
      onTap: () => onOpenThread!(msg),
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
            Icon(Icons.forum_outlined, size: 13,
                color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 4),
            Text('$replyCount개 답글',
                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.primary)),
          ],
        ),
      ),
    ),
  ),
```

Place it inside the file message's body container, similar to the chip placement in `_ChatBubble`.

Then at the call site (around line 491–499) wire the props:

```dart
_FileBubble(
  msg: msg,
  isMine: isMine,
  time: time,
  readCount: ...,
  onOpenThread: widget.onOpenThread,
  replyCount: widget.replyCountFor?.call(msg.effectiveId) ?? 0,
)
```

- [ ] **Step 3: flutter analyze**

```bash
cd frontend && flutter analyze \
  lib/features/chat/widgets/thread_panel.dart \
  lib/features/chat/widgets/chat_messages_list.dart 2>&1 | tail -5
```
Expected: 0 errors.

- [ ] **Step 4: Smoke build**

```bash
cd frontend && flutter build web --release 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/widgets/thread_panel.dart \
        frontend/lib/features/chat/widgets/chat_messages_list.dart
git commit -m "$(cat <<'EOF'
fix(frontend): thread panel watches live parent + _FileBubble supports threads

- ThreadPanel previously used widget.parent (captured snapshot) so a
  deletion broadcast arriving while the panel is open never updated
  the parent header. Now it re-reads the parent from chat_notifier
  state, falling back to the snapshot if the message is gone.

- _FileBubble (file-attachment messages) had no thread plumbing —
  reply chips never appeared on file parents. Added onOpenThread and
  replyCount props mirroring _ChatBubble.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Self-Review

**Spec coverage:** All 8 findings from the QA audit + the REST send gap I caught directly are addressed by Tasks 1–6.

**Placeholder scan:** Each step has either runnable code or a precise pointer + the actual code to insert.

**Type consistency:**
- `findReplies(String chatRoomId, String parentMessageId)` — Task 3 Step 2; called from Task 3 Step 3 controller; tested in Task 3 Step 4.
- `findByChatRoomIdAndParentMessageIdAndDeletedFalseOrderByTimestampAsc` — Task 3 Step 1; called in Task 3 Step 2.
- `WebUnloadHandler.unregister()` — Task 5; defined in `web_unload_handler_web.dart` (existing) + stub.
- `replyOverride` param on `sendMessage` — already shipped in the thread-view branch; Task 4 just calls it from `retryFailedMessage`.

---

## Execution

Use `superpowers:subagent-driven-development`. Branch already created: `fix/qa-bugs-2026-05-10`.

All 6 tasks are small and independent. Sequential dispatch.
