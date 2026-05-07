# Schedule Send + Mention Digest + Drag-Drop Upload — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship 3 independent UX/feature additions to ChatFlow — drag-and-drop file upload (Web), scheduled message send (full stack), and a global @mention digest (full stack) — each with regression tests where the test surface is meaningful.

**Architecture:**
- **Phase A (Drag-Drop):** Frontend-only web feature. `dart:html` event listeners feed the existing `DioClient.uploadFile()` pipeline. No backend change.
- **Phase B (Schedule Send):** New `scheduled_messages` table in chat-service via Flyway V7. New `ScheduledMessageService` polls every 30s; on due time, transforms the row into a normal `ChatMessage` and pipes through the same `MessageSenderService.send()` path. New REST controller for CRUD + cancel. Frontend adds a long-press picker on the send button + a screen listing my scheduled messages.
- **Phase C (Mention Digest):** Reuse the existing `@(\\S+)` pattern from `MessageSenderService`. Server-side: chat-service queries `chat_messages` table directly with a `content LIKE '%@<username>%'` filter (avoids ES reindex risk that was already flagged as deferred). Read-state tracked in Redis via `chatflow:mentions:read:<userId>`. Frontend adds a sidebar entry with unread count + a dedicated screen.

**Tech Stack:** Spring Boot 3.2 + JPA + Flyway 9 (chat-service), Flutter 3.22 + Riverpod 2.5 + GoRouter 14 (frontend), Valkey/Redis (read-receipt set), `dart:html` for web-native drop events.

---

## File Structure

| File | Action | Phase |
|------|--------|-------|
| `frontend/lib/features/chat/widgets/drag_drop_zone.dart` | Create | A |
| `frontend/lib/features/chat/widgets/drag_drop_zone_stub.dart` | Create | A |
| `frontend/lib/features/chat/widgets/drag_drop_zone_web.dart` | Create | A |
| `frontend/lib/features/chat/chat_page.dart` | Modify | A |
| `chat-service/src/main/resources/db/migration/V7__scheduled_messages.sql` | Create | B |
| `chat-service/src/main/java/com/chatflow/chat/entity/ScheduledMessageEntity.java` | Create | B |
| `chat-service/src/main/java/com/chatflow/chat/repository/ScheduledMessageRepository.java` | Create | B |
| `chat-service/src/main/java/com/chatflow/chat/service/ScheduledMessageService.java` | Create | B |
| `chat-service/src/main/java/com/chatflow/chat/controller/ScheduledMessageController.java` | Create | B |
| `chat-service/src/main/java/com/chatflow/chat/dto/ScheduledMessageDto.java` | Create | B |
| `chat-service/src/test/java/com/chatflow/chat/service/ScheduledMessageServiceTest.java` | Create | B |
| `frontend/lib/shared/models/scheduled_message.dart` | Create | B |
| `frontend/lib/features/chat/scheduled_messages_provider.dart` | Create | B |
| `frontend/lib/features/chat/widgets/schedule_send_sheet.dart` | Create | B |
| `frontend/lib/features/chat/screens/scheduled_messages_screen.dart` | Create | B |
| `frontend/lib/features/chat/widgets/chat_input.dart` | Modify | B |
| `frontend/lib/core/routing/app_router.dart` | Modify | B, C |
| `frontend/lib/features/chat/widgets/chat_room_sidebar.dart` | Modify | B, C |
| `chat-service/src/main/java/com/chatflow/chat/service/MentionDigestService.java` | Create | C |
| `chat-service/src/main/java/com/chatflow/chat/controller/MentionDigestController.java` | Create | C |
| `chat-service/src/main/java/com/chatflow/chat/dto/MentionItemDto.java` | Create | C |
| `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java` | Modify | C |
| `chat-service/src/test/java/com/chatflow/chat/service/MentionDigestServiceTest.java` | Create | C |
| `frontend/lib/shared/models/mention_item.dart` | Create | C |
| `frontend/lib/features/chat/mentions_provider.dart` | Create | C |
| `frontend/lib/features/chat/screens/mentions_screen.dart` | Create | C |

---

## Phase A — Drag-and-Drop File Upload (Web-only)

### Task A1: Create the conditional-import scaffolding

**Files:**
- Create: `frontend/lib/features/chat/widgets/drag_drop_zone.dart`
- Create: `frontend/lib/features/chat/widgets/drag_drop_zone_stub.dart`
- Create: `frontend/lib/features/chat/widgets/drag_drop_zone_web.dart`

The codebase already uses the conditional-import pattern (e.g. `apk_downloader.dart` per `CLAUDE.md`). Mirror that.

- [ ] **Step 1: Create the public API file** `drag_drop_zone.dart`:

```dart
import 'package:flutter/material.dart';

import 'drag_drop_zone_stub.dart'
    if (dart.library.html) 'drag_drop_zone_web.dart' as impl;

/// Web-only drag-and-drop overlay. On non-web platforms this is a no-op
/// pass-through that just renders [child].
///
/// When a user drags a file over [child], an overlay appears. On drop,
/// the file is forwarded to [onFileDropped] which the chat page wires
/// into the existing upload pipeline.
class DragDropZone extends StatelessWidget {
  final Widget child;
  final Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped;

  const DragDropZone({
    super.key,
    required this.child,
    required this.onFileDropped,
  });

  @override
  Widget build(BuildContext context) =>
      impl.buildDragDropZone(context, child: child, onFileDropped: onFileDropped);
}
```

- [ ] **Step 2: Create the stub** `drag_drop_zone_stub.dart`:

```dart
import 'package:flutter/material.dart';

Widget buildDragDropZone(
  BuildContext context, {
  required Widget child,
  required Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped,
}) {
  // Non-web: pass through unchanged.
  return child;
}
```

- [ ] **Step 3: Create the web implementation** `drag_drop_zone_web.dart`:

```dart
import 'dart:async';
import 'dart:html' as html;

import 'package:flutter/material.dart';

Widget buildDragDropZone(
  BuildContext context, {
  required Widget child,
  required Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped,
}) {
  return _WebDragDropZone(onFileDropped: onFileDropped, child: child);
}

class _WebDragDropZone extends StatefulWidget {
  final Widget child;
  final Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped;

  const _WebDragDropZone({required this.child, required this.onFileDropped});

  @override
  State<_WebDragDropZone> createState() => _WebDragDropZoneState();
}

class _WebDragDropZoneState extends State<_WebDragDropZone> {
  bool _isDragging = false;
  late final StreamSubscription<html.MouseEvent> _enterSub;
  late final StreamSubscription<html.MouseEvent> _overSub;
  late final StreamSubscription<html.MouseEvent> _leaveSub;
  late final StreamSubscription<html.MouseEvent> _dropSub;

  static const _maxBytes = 50 * 1024 * 1024;

  @override
  void initState() {
    super.initState();
    final body = html.document.body!;
    _enterSub = body.onDragEnter.listen(_onEnter);
    _overSub = body.onDragOver.listen(_onOver);
    _leaveSub = body.onDragLeave.listen(_onLeave);
    _dropSub = body.onDrop.listen(_onDrop);
  }

  void _onEnter(html.MouseEvent event) {
    event.preventDefault();
    if (!_isDragging && mounted) setState(() => _isDragging = true);
  }

  void _onOver(html.MouseEvent event) {
    event.preventDefault();
  }

  void _onLeave(html.MouseEvent event) {
    event.preventDefault();
    // The leave event fires for child elements too — only flip off when the
    // pointer actually exits the window.
    if (event.client.x <= 0 ||
        event.client.y <= 0 ||
        event.client.x >= html.window.innerWidth! ||
        event.client.y >= html.window.innerHeight!) {
      if (mounted) setState(() => _isDragging = false);
    }
  }

  Future<void> _onDrop(html.MouseEvent event) async {
    event.preventDefault();
    if (mounted) setState(() => _isDragging = false);

    final dt = (event as html.MouseEvent).rawEvent;
    final raw = dt is html.DragEvent ? dt : event as html.DragEvent;
    final files = raw.dataTransfer.files;
    if (files == null || files.isEmpty) return;

    final file = files.first;
    if (file.size > _maxBytes) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('파일 크기가 너무 큽니다 (최대 50MB).')),
      );
      return;
    }

    final reader = html.FileReader();
    reader.readAsArrayBuffer(file);
    await reader.onLoad.first;
    final bytes = (reader.result as List<int>);
    final mime = file.type.isEmpty ? 'application/octet-stream' : file.type;
    await widget.onFileDropped(file.name, bytes, mime);
  }

  @override
  void dispose() {
    _enterSub.cancel();
    _overSub.cancel();
    _leaveSub.cancel();
    _dropSub.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        widget.child,
        if (_isDragging)
          Positioned.fill(
            child: IgnorePointer(
              child: Container(
                color: Theme.of(context).colorScheme.primary.withAlpha(40),
                alignment: Alignment.center,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surface,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: Theme.of(context).colorScheme.primary,
                      width: 2,
                      style: BorderStyle.solid,
                    ),
                  ),
                  child: Text(
                    '📎  파일을 여기에 드롭하세요',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
```

- [ ] **Step 4: Verify the package compiles**

Run: `cd /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend && flutter analyze lib/features/chat/widgets/drag_drop_zone.dart lib/features/chat/widgets/drag_drop_zone_stub.dart lib/features/chat/widgets/drag_drop_zone_web.dart`
Expected: 0 issues on these 3 files.

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/features/chat/widgets/drag_drop_zone.dart frontend/lib/features/chat/widgets/drag_drop_zone_stub.dart frontend/lib/features/chat/widgets/drag_drop_zone_web.dart
git commit -m "feat(frontend): add DragDropZone scaffolding (web-conditional)

Conditional-import pattern matching apk_downloader: stub on non-web,
dart:html-backed implementation on web. Listens to body-level
dragenter/dragover/dragleave/drop, shows a translucent overlay during
drag, forwards the dropped file's bytes + mime to a callback.

Confidence: high
Scope-risk: narrow"
```

---

### Task A2: Wire DragDropZone into ChatPage and the existing upload pipeline

**Files:**
- Modify: `frontend/lib/features/chat/chat_page.dart`

The chat message area is rendered around the `ChatMessagesList` widget inside `ChatPage`. Wrap it (or its parent column) with `DragDropZone`. The `onFileDropped` callback should:
1. Call `dioClient.uploadFile()` with the dropped bytes
2. On success, call the existing message-send flow with the resulting `fileUrl` to broadcast a FILE-type message

- [ ] **Step 1: Locate the chat message area in `chat_page.dart`**

Read the file. Find where `ChatMessagesList` (or the central message area `Expanded(child: ...)`) is constructed. The wrap should encompass the messages list AND the input area, so a drop anywhere in the chat half of the page works.

- [ ] **Step 2: Add the import**

```dart
import 'widgets/drag_drop_zone.dart';
```

- [ ] **Step 3: Wrap the chat content area**

Identify the `Expanded`/`Column` that contains `ChatMessagesList` + `ChatInput`. Wrap that with `DragDropZone`. Example shape (the actual file may differ in scaffolding — adapt while preserving the exact widget tree below the wrap):

```dart
DragDropZone(
  onFileDropped: (fileName, bytes, mimeType) async {
    // Reuse the same upload + send flow used by ChatInput's file picker path.
    final dioClient = ref.read(dioClientProvider);
    try {
      final upload = await dioClient.uploadFile(
        fileName: fileName,
        bytes: Uint8List.fromList(bytes),
        mimeType: mimeType,
      );
      final fileUrl = upload['fileUrl']?.toString() ?? '';
      if (fileUrl.isEmpty) return;
      // Emit a FILE message via the same notifier used by chat input.
      // Use the same convention chat_input.dart uses for a file send (see
      // its existing _sendFile / pickFile flow — match exactly so message
      // shape is identical to a click-to-upload).
      ref.read(chatNotifierProvider(roomId).notifier).sendFileMessage(
            fileName: fileName,
            fileUrl: fileUrl,
            fileContentType: mimeType,
          );
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('업로드 실패: $e')),
        );
      }
    }
  },
  child: Column(
    children: [
      Expanded(child: ChatMessagesList(/* unchanged */)),
      ChatInput(/* unchanged */),
    ],
  ),
)
```

> **Implementer note:** Read `chat_input.dart` and locate the existing file-send method (it calls `dioClient.uploadFile` then triggers a CHAT/FILE message). If `sendFileMessage` does not exist on the notifier, find the actual method name used by chat_input's file picker handler and call THAT method here so the shape matches existing message flow.

- [ ] **Step 4: Verify it compiles**

Run: `cd /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend && flutter analyze lib/features/chat/chat_page.dart`
Expected: 0 issues attributable to this change.

- [ ] **Step 5: Manual smoke test (web)**

Run: `cd frontend && flutter run -d chrome`
- Open a chat room.
- Drag any small file (e.g. a screenshot) into the browser window.
- Verify the overlay appears with "파일을 여기에 드롭하세요".
- Drop the file. Verify the upload completes and a FILE message appears in the room.
- Verify drag-leave (drag out of window without dropping) cleanly hides the overlay.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/features/chat/chat_page.dart
git commit -m "feat(frontend): wire DragDropZone into ChatPage upload flow

Wraps the chat message + input column with DragDropZone (web-only
behavior, no-op on Android). Drop forwards bytes + mime to the
existing dioClient.uploadFile() pipeline and emits a FILE message
via the same notifier path as the click-to-upload flow.

Confidence: high
Scope-risk: narrow"
```

---

## Phase B — Schedule Send

### Task B1: Flyway V7 migration for `scheduled_messages`

**Files:**
- Create: `chat-service/src/main/resources/db/migration/V7__scheduled_messages.sql`

- [ ] **Step 1: Create the migration**

```sql
-- V7: Scheduled messages table for the schedule-send feature.
-- Status lifecycle: PENDING -> SENT (success) | CANCELED (user) | FAILED (error)
-- Pending rows are polled every 30s by ScheduledMessageService.

CREATE TABLE scheduled_messages (
    id              BIGSERIAL PRIMARY KEY,
    chat_room_id    VARCHAR(50)  NOT NULL,
    user_id         VARCHAR(50)  NOT NULL,
    username        VARCHAR(100) NOT NULL,
    content         TEXT         NOT NULL,
    scheduled_at    TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    sent_message_id VARCHAR(36),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    error_message   TEXT
);

CREATE INDEX idx_scheduled_messages_status_due
    ON scheduled_messages (status, scheduled_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_scheduled_messages_user
    ON scheduled_messages (user_id, status, scheduled_at DESC);
```

- [ ] **Step 2: Verify Flyway picks it up**

Run: `./gradlew :chat-service:compileJava`
Expected: BUILD SUCCESSFUL.

The migration won't run yet (it runs at app startup). We'll verify execution at the end of Phase B.

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/main/resources/db/migration/V7__scheduled_messages.sql
git commit -m "feat(chat-service): V7 scheduled_messages table

Backing store for the schedule-send feature. PENDING rows are polled
every 30s and converted to live ChatMessages when their scheduled_at
arrives. Two indexes: a partial index on (status='PENDING', scheduled_at)
for the poller's hot path, and (user_id, status, scheduled_at DESC) for
the user-facing list view.

Confidence: high
Scope-risk: narrow"
```

---

### Task B2: `ScheduledMessageEntity` + Repository

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/entity/ScheduledMessageEntity.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/repository/ScheduledMessageRepository.java`

- [ ] **Step 1: Create the entity**

```java
package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_messages")
public class ScheduledMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String chatRoomId;

    @Column(nullable = false, length = 50)
    private String userId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ScheduledMessageStatus status = ScheduledMessageStatus.PENDING;

    @Column(length = 36)
    private String sentMessageId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ScheduledMessageStatus { PENDING, SENT, CANCELED, FAILED }
}
```

- [ ] **Step 2: Create the repository**

```java
package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessageEntity, Long> {

    @Query("SELECT s FROM ScheduledMessageEntity s " +
           "WHERE s.status = 'PENDING' AND s.scheduledAt <= :now " +
           "ORDER BY s.scheduledAt ASC")
    List<ScheduledMessageEntity> findDueForSending(@Param("now") LocalDateTime now);

    List<ScheduledMessageEntity> findByUserIdAndStatusOrderByScheduledAtDesc(
            String userId, ScheduledMessageStatus status);

    Optional<ScheduledMessageEntity> findByIdAndUserId(Long id, String userId);
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/entity/ScheduledMessageEntity.java chat-service/src/main/java/com/chatflow/chat/repository/ScheduledMessageRepository.java
git commit -m "feat(chat-service): ScheduledMessageEntity + repository

Entity with status enum (PENDING/SENT/CANCELED/FAILED), @PrePersist /
@PreUpdate timestamps, sent_message_id link to the resulting ChatMessage.
Repository exposes findDueForSending(now) for the poller and a
user-scoped list query.

Confidence: high
Scope-risk: narrow"
```

---

### Task B3: `ScheduledMessageService` with TDD

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/service/ScheduledMessageService.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/service/ScheduledMessageServiceTest.java`

- [ ] **Step 1: Write the failing test FIRST**

```java
package com.chatflow.chat.service;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.chatflow.chat.repository.ScheduledMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledMessageServiceTest {

    @Mock private ScheduledMessageRepository repository;
    @Mock private MessageSenderService messageSenderService;

    private ScheduledMessageService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledMessageService(repository, messageSenderService);
    }

    private ScheduledMessageEntity sample(Long id, ScheduledMessageStatus status, LocalDateTime when) {
        return ScheduledMessageEntity.builder()
                .id(id)
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("hello future")
                .scheduledAt(when)
                .status(status)
                .build();
    }

    @Test
    void schedule_persistsPendingRowAndReturnsIt() {
        when(repository.save(any())).thenAnswer(inv -> {
            ScheduledMessageEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        ScheduledMessageEntity saved = service.schedule(
                "room-1", "user-1", "alice", "hi later",
                LocalDateTime.now().plusMinutes(30));

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo(ScheduledMessageStatus.PENDING);
        assertThat(saved.getContent()).isEqualTo("hi later");
    }

    @Test
    void schedule_rejectsPastTimes() {
        assertThatThrownBy(() -> service.schedule(
                "room-1", "user-1", "alice", "x",
                LocalDateTime.now().minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancel_marksRowCanceled_whenOwnerMatches() {
        when(repository.findByIdAndUserId(7L, "user-1"))
                .thenReturn(Optional.of(sample(7L, ScheduledMessageStatus.PENDING,
                        LocalDateTime.now().plusHours(1))));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledMessageEntity result = service.cancel(7L, "user-1");

        assertThat(result.getStatus()).isEqualTo(ScheduledMessageStatus.CANCELED);
    }

    @Test
    void cancel_throws_whenNotOwner() {
        when(repository.findByIdAndUserId(7L, "intruder")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(7L, "intruder"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_isNoOp_whenAlreadyTerminal() {
        when(repository.findByIdAndUserId(7L, "user-1"))
                .thenReturn(Optional.of(sample(7L, ScheduledMessageStatus.SENT,
                        LocalDateTime.now().minusMinutes(10))));

        ScheduledMessageEntity result = service.cancel(7L, "user-1");

        assertThat(result.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
        verify(repository, never()).save(any());
    }

    @Test
    void deliverDue_skipsEmptyBatch() {
        when(repository.findDueForSending(any())).thenReturn(List.of());
        service.deliverDue();
        verify(messageSenderService, never()).send(any());
    }

    @Test
    void deliverDue_sendsAndMarksSent() {
        ScheduledMessageEntity row = sample(11L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(row));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deliverDue();

        ArgumentCaptor<ChatMessage> sent = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageSenderService).send(sent.capture());
        assertThat(sent.getValue().getContent()).isEqualTo("hello future");
        assertThat(sent.getValue().getChatRoomId()).isEqualTo("room-1");
        assertThat(sent.getValue().getUserId()).isEqualTo("user-1");
        assertThat(sent.getValue().getMessageId()).isNotNull();
        assertThat(row.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
        assertThat(row.getSentMessageId()).isNotNull();
    }

    @Test
    void deliverDue_marksFailedOnSendError_andContinuesNextRow() {
        ScheduledMessageEntity bad = sample(1L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(2));
        ScheduledMessageEntity good = sample(2L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(bad, good));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        doThrow(new RuntimeException("kafka down")).when(messageSenderService)
                .send(org.mockito.ArgumentMatchers.argThat(m -> "1".equals(m.getMessageId().substring(0, 1)) || true));

        // Re-stub more precisely: throw on first, succeed on second
        org.mockito.Mockito.reset(messageSenderService);
        doThrow(new RuntimeException("kafka down"))
                .doNothing()
                .when(messageSenderService).send(any());

        service.deliverDue();

        assertThat(bad.getStatus()).isEqualTo(ScheduledMessageStatus.FAILED);
        assertThat(bad.getErrorMessage()).contains("kafka down");
        assertThat(good.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
    }
}
```

- [ ] **Step 2: Run the test, watch it fail to compile**

Run: `./gradlew :chat-service:test --tests com.chatflow.chat.service.ScheduledMessageServiceTest`
Expected: COMPILE FAIL (`ScheduledMessageService` doesn't exist).

- [ ] **Step 3: Create the service**

```java
package com.chatflow.chat.service;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.chatflow.chat.repository.ScheduledMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledMessageService {

    private final ScheduledMessageRepository repository;
    private final MessageSenderService messageSenderService;

    @Transactional
    public ScheduledMessageEntity schedule(
            String chatRoomId, String userId, String username,
            String content, LocalDateTime scheduledAt) {
        if (scheduledAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("scheduledAt must be in the future");
        }
        ScheduledMessageEntity entity = ScheduledMessageEntity.builder()
                .chatRoomId(chatRoomId)
                .userId(userId)
                .username(username)
                .content(content)
                .scheduledAt(scheduledAt)
                .status(ScheduledMessageStatus.PENDING)
                .build();
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ScheduledMessageEntity> listMine(String userId) {
        return repository.findByUserIdAndStatusOrderByScheduledAtDesc(
                userId, ScheduledMessageStatus.PENDING);
    }

    @Transactional
    public ScheduledMessageEntity cancel(Long id, String userId) {
        ScheduledMessageEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled message not found or not owned: id=" + id));
        if (entity.getStatus() != ScheduledMessageStatus.PENDING) {
            log.info("Cancel no-op on {} (status={})", id, entity.getStatus());
            return entity;
        }
        entity.setStatus(ScheduledMessageStatus.CANCELED);
        return repository.save(entity);
    }

    /**
     * Polled by Spring's task scheduler. Picks up rows whose scheduledAt
     * has arrived, hands them to the live message sender, and marks them
     * SENT or FAILED accordingly. A failure on one row does not abort
     * the rest of the batch.
     */
    @Scheduled(fixedDelay = 30_000L) // 30s
    @Transactional
    public void deliverDue() {
        List<ScheduledMessageEntity> due = repository.findDueForSending(LocalDateTime.now());
        if (due.isEmpty()) return;
        log.info("Delivering {} scheduled message(s)", due.size());

        for (ScheduledMessageEntity row : due) {
            try {
                ChatMessage msg = new ChatMessage();
                msg.setMessageId(UUID.randomUUID().toString());
                msg.setChatRoomId(row.getChatRoomId());
                msg.setUserId(row.getUserId());
                msg.setUsername(row.getUsername());
                msg.setContent(row.getContent());
                msg.setType(ChatMessage.MessageType.CHAT);
                msg.setTimestamp(LocalDateTime.now());

                messageSenderService.send(msg);

                row.setStatus(ScheduledMessageStatus.SENT);
                row.setSentMessageId(msg.getMessageId());
                repository.save(row);
            } catch (Exception e) {
                log.error("Scheduled delivery failed for id={}: {}", row.getId(), e.getMessage(), e);
                row.setStatus(ScheduledMessageStatus.FAILED);
                row.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                repository.save(row);
            }
        }
    }
}
```

> **Note:** The application must have `@EnableScheduling` enabled somewhere (it should already, since other `@Scheduled` methods exist in the codebase like `OutboxPoller`. If `./gradlew :chat-service:test` reveals the scheduler isn't picking it up, add `@EnableScheduling` to the main application class.)

- [ ] **Step 4: Re-run the test**

Run: `./gradlew :chat-service:test --tests com.chatflow.chat.service.ScheduledMessageServiceTest`
Expected: 8/8 PASS. (One of the test mocking patterns is intentionally finicky; if `deliverDue_marksFailedOnSendError_andContinuesNextRow` fails because of the awkward mock-reset, simplify it inline so it works — the *behavior* under test must be: failure on one row does not stop subsequent rows.)

- [ ] **Step 5: Run the full chat-service suite**

Run: `./gradlew :chat-service:test`
Expected: All previously-passing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/ScheduledMessageService.java chat-service/src/test/java/com/chatflow/chat/service/ScheduledMessageServiceTest.java
git commit -m "feat(chat-service): ScheduledMessageService with @Scheduled poller

CRUD + 30s poller. schedule() rejects past times. deliverDue() picks
PENDING rows whose scheduledAt has arrived, hands them to
MessageSenderService.send (which already handles outbox + STOMP +
Kafka), then marks SENT. A row-level exception is caught and the
row marked FAILED so the rest of the batch continues.

Constraint: must reuse MessageSenderService — never bypass mute / FCM /
mention pipelines for delivery
Confidence: high
Scope-risk: narrow
Directive: do not make this poller transactional-per-row by default;
  one outer @Transactional batches the saves correctly. If failure
  isolation becomes a perf problem, switch to per-row TX with care."
```

---

### Task B4: REST controller + DTO

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/dto/ScheduledMessageDto.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/controller/ScheduledMessageController.java`

- [ ] **Step 1: DTO**

```java
package com.chatflow.chat.dto;

import com.chatflow.chat.entity.ScheduledMessageEntity;

import java.time.LocalDateTime;

public record ScheduledMessageDto(
        Long id,
        String chatRoomId,
        String content,
        LocalDateTime scheduledAt,
        String status,
        LocalDateTime createdAt
) {
    public static ScheduledMessageDto from(ScheduledMessageEntity e) {
        return new ScheduledMessageDto(
                e.getId(),
                e.getChatRoomId(),
                e.getContent(),
                e.getScheduledAt(),
                e.getStatus().name(),
                e.getCreatedAt()
        );
    }
}
```

- [ ] **Step 2: Controller**

```java
package com.chatflow.chat.controller;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.service.ScheduledMessageService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/scheduled-messages")
@RequiredArgsConstructor
public class ScheduledMessageController {

    private final ScheduledMessageService service;

    @PostMapping
    public ResponseEntity<ApiResponse<ScheduledMessageDto>> schedule(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username") String username) {
        String chatRoomId = (String) body.get("chatRoomId");
        String content = (String) body.get("content");
        String scheduledAtStr = (String) body.get("scheduledAt");
        if (chatRoomId == null || content == null || scheduledAtStr == null) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("chatRoomId, content, scheduledAt are required"));
        }
        var saved = service.schedule(chatRoomId, userId, username, content,
                LocalDateTime.parse(scheduledAtStr));
        return ResponseEntity.ok(ApiResponse.ok(ScheduledMessageDto.from(saved)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledMessageDto>>> list(
            @RequestHeader(value = "X-User-Id") String userId) {
        var items = service.listMine(userId).stream()
                .map(ScheduledMessageDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduledMessageDto>> cancel(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id") String userId) {
        var canceled = service.cancel(id, userId);
        return ResponseEntity.ok(ApiResponse.ok(ScheduledMessageDto.from(canceled)));
    }
}
```

- [ ] **Step 3: Compile + test**

Run: `./gradlew :chat-service:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/dto/ScheduledMessageDto.java chat-service/src/main/java/com/chatflow/chat/controller/ScheduledMessageController.java
git commit -m "feat(chat-service): REST endpoints for scheduled messages

POST /api/chat/scheduled-messages — schedule a new one
GET  /api/chat/scheduled-messages — list mine (PENDING only)
DELETE /api/chat/scheduled-messages/{id} — cancel mine

All three require X-User-Id (set by gateway from JWT). Cancel is
ownership-checked in the service layer, not at the SQL boundary —
so other users' messages return 'not found / not owned' instead of
404 vs 403.

Confidence: high
Scope-risk: narrow"
```

---

### Task B5: Frontend — `ScheduledMessage` model + provider

**Files:**
- Create: `frontend/lib/shared/models/scheduled_message.dart`
- Create: `frontend/lib/features/chat/scheduled_messages_provider.dart`

- [ ] **Step 1: Model**

```dart
class ScheduledMessage {
  final int id;
  final String chatRoomId;
  final String content;
  final String scheduledAt; // ISO string
  final String status;      // PENDING | SENT | CANCELED | FAILED
  final String createdAt;

  ScheduledMessage({
    required this.id,
    required this.chatRoomId,
    required this.content,
    required this.scheduledAt,
    required this.status,
    required this.createdAt,
  });

  factory ScheduledMessage.fromJson(Map<String, dynamic> json) => ScheduledMessage(
        id: (json['id'] as num).toInt(),
        chatRoomId: json['chatRoomId']?.toString() ?? '',
        content: json['content']?.toString() ?? '',
        scheduledAt: json['scheduledAt']?.toString() ?? '',
        status: json['status']?.toString() ?? 'PENDING',
        createdAt: json['createdAt']?.toString() ?? '',
      );

  DateTime get scheduledAtDateTime =>
      DateTime.tryParse(scheduledAt) ?? DateTime.now();
}
```

- [ ] **Step 2: Provider**

```dart
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/scheduled_message.dart';

class ScheduledMessagesNotifier extends StateNotifier<AsyncValue<List<ScheduledMessage>>> {
  final Dio _dio;
  ScheduledMessagesNotifier(this._dio) : super(const AsyncValue.loading()) {
    refresh();
  }

  Future<void> refresh() async {
    state = const AsyncValue.loading();
    try {
      final resp = await _dio.get('/api/chat/scheduled-messages');
      final data = resp.data;
      List<dynamic> raw;
      if (data is Map && data['data'] is List) {
        raw = data['data'] as List;
      } else if (data is List) {
        raw = data;
      } else {
        raw = const [];
      }
      final items = raw
          .map((e) => ScheduledMessage.fromJson(e as Map<String, dynamic>))
          .toList();
      state = AsyncValue.data(items);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  Future<ScheduledMessage> schedule({
    required String chatRoomId,
    required String content,
    required DateTime scheduledAt,
  }) async {
    final resp = await _dio.post(
      '/api/chat/scheduled-messages',
      data: {
        'chatRoomId': chatRoomId,
        'content': content,
        'scheduledAt': scheduledAt.toIso8601String(),
      },
    );
    final data = resp.data;
    final inner = (data is Map && data['data'] is Map)
        ? data['data'] as Map<String, dynamic>
        : data as Map<String, dynamic>;
    final saved = ScheduledMessage.fromJson(inner);
    final current = state.value ?? const [];
    state = AsyncValue.data([saved, ...current]);
    return saved;
  }

  Future<void> cancel(int id) async {
    await _dio.delete('/api/chat/scheduled-messages/$id');
    final current = state.value ?? const [];
    state = AsyncValue.data(current.where((m) => m.id != id).toList());
  }
}

final scheduledMessagesProvider = StateNotifierProvider<
    ScheduledMessagesNotifier, AsyncValue<List<ScheduledMessage>>>((ref) {
  final dio = ref.read(dioClientProvider).dio;
  return ScheduledMessagesNotifier(dio);
});
```

- [ ] **Step 3: Compile**

Run: `cd frontend && flutter analyze lib/shared/models/scheduled_message.dart lib/features/chat/scheduled_messages_provider.dart`
Expected: 0 issues.

- [ ] **Step 4: Commit**

```bash
git add frontend/lib/shared/models/scheduled_message.dart frontend/lib/features/chat/scheduled_messages_provider.dart
git commit -m "feat(frontend): ScheduledMessage model + Riverpod provider

StateNotifier wraps the /api/chat/scheduled-messages CRUD endpoints,
unwraps ApiResponse via the same pattern Tasks 1/2/6 introduced (Map
data + bare-list fallback for cached responses).

Confidence: high
Scope-risk: narrow"
```

---

### Task B6: Frontend — Schedule picker sheet + send-button long-press wiring

**Files:**
- Create: `frontend/lib/features/chat/widgets/schedule_send_sheet.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_input.dart`

- [ ] **Step 1: Schedule sheet**

```dart
import 'package:flutter/material.dart';

/// A modal bottom sheet that lets the user pick a future DateTime.
/// Returns null if dismissed without picking.
Future<DateTime?> showScheduleSendSheet(BuildContext context) async {
  final now = DateTime.now();
  final presets = <(_PresetLabel, DateTime)>[
    (_PresetLabel('1시간 후'), now.add(const Duration(hours: 1))),
    (_PresetLabel('오늘 저녁 6시'),
        DateTime(now.year, now.month, now.day, 18, 0)),
    (_PresetLabel('내일 오전 9시'),
        DateTime(now.year, now.month, now.day, 9, 0).add(const Duration(days: 1))),
  ];
  // Filter past presets (e.g. evening preset before 18:00 of today is fine; after 18:00 it's past)
  final validPresets = presets.where((p) => p.$2.isAfter(now)).toList();

  return showModalBottomSheet<DateTime>(
    context: context,
    isScrollControlled: true,
    builder: (ctx) {
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('예약 발송 시각',
                  style: Theme.of(ctx).textTheme.titleMedium),
              const SizedBox(height: 12),
              ...validPresets.map((p) => ListTile(
                    title: Text(p.$1.label),
                    subtitle: Text(_format(p.$2)),
                    onTap: () => Navigator.of(ctx).pop(p.$2),
                  )),
              const Divider(),
              ListTile(
                leading: const Icon(Icons.event),
                title: const Text('직접 선택...'),
                onTap: () async {
                  final picked = await _pickCustom(ctx);
                  if (picked != null) Navigator.of(ctx).pop(picked);
                },
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _PresetLabel {
  final String label;
  _PresetLabel(this.label);
}

String _format(DateTime dt) {
  return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')} '
      '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
}

Future<DateTime?> _pickCustom(BuildContext context) async {
  final now = DateTime.now();
  final date = await showDatePicker(
    context: context,
    firstDate: now,
    lastDate: now.add(const Duration(days: 365)),
    initialDate: now.add(const Duration(hours: 1)),
  );
  if (date == null) return null;
  if (!context.mounted) return null;
  final time = await showTimePicker(
    context: context,
    initialTime: TimeOfDay.now().replacing(minute: (TimeOfDay.now().minute ~/ 5) * 5),
  );
  if (time == null) return null;
  final picked = DateTime(date.year, date.month, date.day, time.hour, time.minute);
  if (!picked.isAfter(now)) return null;
  return picked;
}
```

- [ ] **Step 2: Wire long-press into ChatInput's send button**

Find the IconButton (or similar) in `chat_input.dart` that triggers send. Wrap it with `GestureDetector` so:
- Tap (existing behavior): immediate send
- Long-press: open `showScheduleSendSheet`, on result POST to `scheduledMessagesProvider.schedule()`, clear the input, show a SnackBar

Add the import:
```dart
import 'schedule_send_sheet.dart';
import '../scheduled_messages_provider.dart';
```

Replace the send-icon button block (locate around line 192 `widget.onSend(text, priority: _priority);`) with a `GestureDetector` wrapper:

```dart
GestureDetector(
  onLongPress: () async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;
    final when = await showScheduleSendSheet(context);
    if (when == null) return;
    if (!context.mounted) return;
    final roomId = widget.roomId;
    try {
      await ref.read(scheduledMessagesProvider.notifier).schedule(
            chatRoomId: roomId,
            content: text,
            scheduledAt: when,
          );
      _controller.clear();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('메시지가 예약되었습니다.')),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('예약 실패: $e')),
        );
      }
    }
  },
  child: IconButton(
    icon: const Icon(Icons.send),
    tooltip: '보내기 (길게 눌러 예약)',
    onPressed: () { /* existing send body */ },
  ),
)
```

> **Implementer:** Read `chat_input.dart` carefully and adapt — the actual send button construction may use different widgets / state hooks. Preserve all existing behavior on tap; only add long-press.

- [ ] **Step 3: Tests + analyze**

Run: `cd frontend && flutter analyze && flutter test`
Expected: 0 new issues; tests still pass.

- [ ] **Step 4: Commit**

```bash
git add frontend/lib/features/chat/widgets/schedule_send_sheet.dart frontend/lib/features/chat/widgets/chat_input.dart
git commit -m "feat(frontend): schedule-send picker + long-press send button

Long-press on the send button opens a modal sheet with three time
presets (1h, today 18:00, tomorrow 9:00) plus a custom date+time
picker. On commit, the input text is posted to /api/chat/scheduled-messages
and the input is cleared.

Confidence: high
Scope-risk: narrow"
```

---

### Task B7: Frontend — Scheduled messages screen + sidebar entry + route

**Files:**
- Create: `frontend/lib/features/chat/screens/scheduled_messages_screen.dart`
- Modify: `frontend/lib/core/routing/app_router.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`

- [ ] **Step 1: Screen**

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../scheduled_messages_provider.dart';

class ScheduledMessagesScreen extends ConsumerWidget {
  const ScheduledMessagesScreen({super.key});

  String _format(DateTime dt) =>
      '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')} '
      '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(scheduledMessagesProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('예약된 메시지'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(scheduledMessagesProvider.notifier).refresh(),
          ),
        ],
      ),
      body: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('불러오기 실패: $e')),
        data: (items) => items.isEmpty
            ? const Center(child: Text('예약된 메시지가 없습니다.'))
            : ListView.builder(
                itemCount: items.length,
                itemBuilder: (ctx, i) {
                  final item = items[i];
                  return ListTile(
                    title: Text(item.content,
                        maxLines: 2, overflow: TextOverflow.ellipsis),
                    subtitle: Text(
                        '${item.chatRoomId}  ·  ${_format(item.scheduledAtDateTime)}'),
                    trailing: IconButton(
                      icon: const Icon(Icons.cancel_outlined),
                      tooltip: '취소',
                      onPressed: () async {
                        final ok = await showDialog<bool>(
                          context: ctx,
                          builder: (dctx) => AlertDialog(
                            title: const Text('예약 취소'),
                            content: const Text('이 예약을 취소하시겠습니까?'),
                            actions: [
                              TextButton(
                                onPressed: () => Navigator.of(dctx).pop(false),
                                child: const Text('아니오'),
                              ),
                              TextButton(
                                onPressed: () => Navigator.of(dctx).pop(true),
                                child: const Text('취소'),
                              ),
                            ],
                          ),
                        );
                        if (ok == true) {
                          await ref
                              .read(scheduledMessagesProvider.notifier)
                              .cancel(item.id);
                        }
                      },
                    ),
                  );
                },
              ),
      ),
    );
  }
}
```

- [ ] **Step 2: Route**

In `frontend/lib/core/routing/app_router.dart`, add inside the `routes:` list (before `/invite/:token`):

```dart
GoRoute(
  path: '/scheduled',
  builder: (context, state) => const ScheduledMessagesScreen(),
),
```

Add the import at the top:

```dart
import '../../features/chat/screens/scheduled_messages_screen.dart';
```

- [ ] **Step 3: Sidebar entry**

In `chat_room_sidebar.dart`, add a list tile (above or near the bookmarks entry — read the file to find the right spot). Suggested:

```dart
ListTile(
  leading: const Icon(Icons.schedule_send),
  title: const Text('예약된 메시지'),
  onTap: () => context.go('/scheduled'),
),
```

(Make sure `import 'package:go_router/go_router.dart';` is present — it should already be.)

- [ ] **Step 4: Verify**

Run: `cd frontend && flutter analyze && flutter test`
Expected: green.

- [ ] **Step 5: Manual smoke test**

Spin up `flutter run -d chrome`:
- Type a message, long-press send, pick "1시간 후" → verify SnackBar + input cleared.
- Click sidebar "예약된 메시지" → verify list shows the new entry.
- Click cancel → verify the entry disappears.
- Set a preset to ~1 minute in the future via "직접 선택" → wait for poller (up to 30s past scheduledAt) → verify message appears in the chat room.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/features/chat/screens/scheduled_messages_screen.dart frontend/lib/core/routing/app_router.dart frontend/lib/features/chat/widgets/chat_room_sidebar.dart
git commit -m "feat(frontend): scheduled messages screen + sidebar entry

GoRoute /scheduled. List view with cancel-with-confirm. Sidebar
'예약된 메시지' entry.

Confidence: high
Scope-risk: narrow"
```

---

## Phase C — @Mention Digest

### Task C1: Backend — repository query + DTO

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/dto/MentionItemDto.java`

- [ ] **Step 1: Add the query method**

Add to `ChatMessageRepository.java`:

```java
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

// Inside the interface body:
@Query("SELECT m FROM ChatMessageEntity m " +
       "WHERE m.content LIKE CONCAT('%@', :username, '%') " +
       "  AND m.timestamp >= :since " +
       "  AND m.username <> :username " +
       "ORDER BY m.timestamp DESC")
List<ChatMessageEntity> findMentionsOf(
        @Param("username") String username,
        @Param("since") LocalDateTime since);
```

> Why content LIKE: avoids ES reindex risk that's already deferred from the prior fix branch. Self-mentions (m.username = :username) are excluded.

- [ ] **Step 2: DTO**

```java
package com.chatflow.chat.dto;

import com.chatflow.chat.entity.ChatMessageEntity;

import java.time.LocalDateTime;

public record MentionItemDto(
        String messageId,
        String chatRoomId,
        String fromUsername,
        String contentPreview,
        LocalDateTime timestamp,
        boolean read
) {
    public static MentionItemDto from(ChatMessageEntity e, boolean read) {
        String preview = e.getContent() != null && e.getContent().length() > 140
                ? e.getContent().substring(0, 140) + "..."
                : e.getContent();
        return new MentionItemDto(
                e.getMessageId(),
                e.getChatRoomId(),
                e.getUsername(),
                preview != null ? preview : "",
                e.getTimestamp(),
                read
        );
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java chat-service/src/main/java/com/chatflow/chat/dto/MentionItemDto.java
git commit -m "feat(chat-service): mention-digest repo query + DTO

JPQL LIKE-based query over chat_messages.content for '@<username>'.
Avoids the ES reindex needed to switch ChatMessageDocument to a
properly tokenized mention field (deferred — see f660d1f directive).
Self-mentions filtered. DTO exposes a 140-char preview + read flag.

Confidence: high
Scope-risk: narrow
Directive: if mention volume grows, replace LIKE with a proper
  mentioned_user_ids column on chat_messages for index-friendly query."
```

---

### Task C2: Backend — service with read-state tracking + controller, with TDD

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/service/MentionDigestService.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/controller/MentionDigestController.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/service/MentionDigestServiceTest.java`

Read state lives in Redis: SET key `chatflow:mentions:read:<userId>` of seen messageIds.

- [ ] **Step 1: Failing test**

```java
package com.chatflow.chat.service;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MentionDigestServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;

    private MentionDigestService service;

    @BeforeEach
    void setUp() {
        service = new MentionDigestService(chatMessageRepository, redisTemplate);
    }

    private ChatMessageEntity msg(String id, String fromUser, String content, LocalDateTime when) {
        ChatMessageEntity e = new ChatMessageEntity();
        e.setMessageId(id);
        e.setChatRoomId("room-1");
        e.setUsername(fromUser);
        e.setContent(content);
        e.setTimestamp(when);
        return e;
    }

    @Test
    void list_marksReadStatusFromRedisSet() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of(
                        msg("m1", "bob", "@alice hey", LocalDateTime.now()),
                        msg("m2", "carol", "@alice yo", LocalDateTime.now().minusMinutes(5))));
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chatflow:mentions:read:user-alice"))
                .thenReturn(Set.of("m1"));

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).messageId()).isEqualTo("m1");
        assertThat(result.get(0).read()).isTrue();
        assertThat(result.get(1).read()).isFalse();
    }

    @Test
    void unreadCount_excludesAlreadyReadMessages() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of(
                        msg("m1", "bob", "@alice hey", LocalDateTime.now()),
                        msg("m2", "carol", "@alice yo", LocalDateTime.now()),
                        msg("m3", "dave", "@alice woo", LocalDateTime.now())));
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("m2"));

        long count = service.unreadCount("user-alice", "alice", 30);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    void markRead_addsMessageIdToRedisSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        service.markRead("user-alice", "m42");

        verify(setOps).add("chatflow:mentions:read:user-alice", "m42");
    }

    @Test
    void markAllRead_addsAllCurrentMentionMessageIds() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of(
                        msg("m1", "bob", "@alice", LocalDateTime.now()),
                        msg("m2", "carol", "@alice", LocalDateTime.now())));
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        service.markAllRead("user-alice", "alice", 30);

        verify(setOps).add(eq("chatflow:mentions:read:user-alice"),
                eq("m1"), eq("m2"));
    }
}
```

- [ ] **Step 2: Run, watch fail**

Run: `./gradlew :chat-service:test --tests com.chatflow.chat.service.MentionDigestServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Service**

```java
package com.chatflow.chat.service;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MentionDigestService {

    private final ChatMessageRepository chatMessageRepository;
    private final StringRedisTemplate redisTemplate;

    private static final int MAX_DAYS = 365;

    private String readKey(String userId) {
        return "chatflow:mentions:read:" + userId;
    }

    public List<MentionItemDto> list(String userId, String username, int days) {
        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        List<ChatMessageEntity> rows = chatMessageRepository.findMentionsOf(username, since);
        Set<String> readSet = readSet(userId);
        return rows.stream()
                .map(e -> MentionItemDto.from(e, readSet.contains(e.getMessageId())))
                .collect(Collectors.toList());
    }

    public long unreadCount(String userId, String username, int days) {
        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        List<ChatMessageEntity> rows = chatMessageRepository.findMentionsOf(username, since);
        Set<String> readSet = readSet(userId);
        return rows.stream().filter(e -> !readSet.contains(e.getMessageId())).count();
    }

    public void markRead(String userId, String messageId) {
        redisTemplate.opsForSet().add(readKey(userId), messageId);
    }

    public void markAllRead(String userId, String username, int days) {
        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        List<ChatMessageEntity> rows = chatMessageRepository.findMentionsOf(username, since);
        if (rows.isEmpty()) return;
        String[] ids = rows.stream()
                .map(ChatMessageEntity::getMessageId)
                .toArray(String[]::new);
        redisTemplate.opsForSet().add(readKey(userId), ids);
    }

    private Set<String> readSet(String userId) {
        Set<String> members = redisTemplate.opsForSet().members(readKey(userId));
        return members == null ? Set.of() : members;
    }
}
```

- [ ] **Step 4: Controller**

```java
package com.chatflow.chat.controller;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.service.MentionDigestService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/mentions")
@RequiredArgsConstructor
public class MentionDigestController {

    private final MentionDigestService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MentionItemDto>>> list(
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username") String username,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(userId, username, days)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username") String username,
            @RequestParam(defaultValue = "30") int days) {
        long count = service.unreadCount(userId, username, days);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", count)));
    }

    @PostMapping("/{messageId}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @PathVariable String messageId,
            @RequestHeader(value = "X-User-Id") String userId) {
        service.markRead(userId, messageId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllRead(
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username") String username,
            @RequestParam(defaultValue = "30") int days) {
        service.markAllRead(userId, username, days);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
```

- [ ] **Step 5: Re-run tests**

Run: `./gradlew :chat-service:test`
Expected: 4/4 new tests pass + entire chat-service suite green.

- [ ] **Step 6: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/MentionDigestService.java chat-service/src/main/java/com/chatflow/chat/controller/MentionDigestController.java chat-service/src/test/java/com/chatflow/chat/service/MentionDigestServiceTest.java
git commit -m "feat(chat-service): MentionDigestService + REST endpoints

GET /api/chat/mentions?days=30 — list
GET /api/chat/mentions/unread-count?days=30 — number badge
POST /api/chat/mentions/{messageId}/read — mark single read
POST /api/chat/mentions/read-all?days=30 — mark all in window read

Read state in Redis SET 'chatflow:mentions:read:<userId>'. Unbounded
membership growth is fine in practice (one user mentions per day are
bounded); revisit with a TTL or periodic prune if it ever bites.

Confidence: high
Scope-risk: narrow
Not-tested: full E2E with a live message containing '@username'"
```

---

### Task C3: Frontend — model + provider

**Files:**
- Create: `frontend/lib/shared/models/mention_item.dart`
- Create: `frontend/lib/features/chat/mentions_provider.dart`

- [ ] **Step 1: Model**

```dart
class MentionItem {
  final String messageId;
  final String chatRoomId;
  final String fromUsername;
  final String contentPreview;
  final String timestamp;
  final bool read;

  MentionItem({
    required this.messageId,
    required this.chatRoomId,
    required this.fromUsername,
    required this.contentPreview,
    required this.timestamp,
    required this.read,
  });

  factory MentionItem.fromJson(Map<String, dynamic> json) => MentionItem(
        messageId: json['messageId']?.toString() ?? '',
        chatRoomId: json['chatRoomId']?.toString() ?? '',
        fromUsername: json['fromUsername']?.toString() ?? '',
        contentPreview: json['contentPreview']?.toString() ?? '',
        timestamp: json['timestamp']?.toString() ?? '',
        read: json['read'] == true,
      );

  DateTime get when => DateTime.tryParse(timestamp) ?? DateTime.now();
}
```

- [ ] **Step 2: Provider**

```dart
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/mention_item.dart';

class MentionsState {
  final AsyncValue<List<MentionItem>> items;
  final int unreadCount;
  const MentionsState({required this.items, required this.unreadCount});

  MentionsState copyWith({
    AsyncValue<List<MentionItem>>? items,
    int? unreadCount,
  }) =>
      MentionsState(
        items: items ?? this.items,
        unreadCount: unreadCount ?? this.unreadCount,
      );

  static const empty = MentionsState(items: AsyncValue.data([]), unreadCount: 0);
}

class MentionsNotifier extends StateNotifier<MentionsState> {
  final Dio _dio;
  MentionsNotifier(this._dio) : super(MentionsState.empty) {
    refresh();
    refreshUnreadCount();
  }

  static List<dynamic> _unwrapList(dynamic data) {
    if (data is Map && data['data'] is List) return data['data'] as List;
    if (data is List) return data;
    return const [];
  }

  static Map<String, dynamic> _unwrapMap(dynamic data) {
    if (data is Map && data['data'] is Map) return (data['data'] as Map).cast<String, dynamic>();
    if (data is Map) return data.cast<String, dynamic>();
    return const {};
  }

  Future<void> refresh({int days = 30}) async {
    state = state.copyWith(items: const AsyncValue.loading());
    try {
      final resp = await _dio.get('/api/chat/mentions', queryParameters: {'days': days});
      final raw = _unwrapList(resp.data);
      final items = raw
          .map((e) => MentionItem.fromJson(e as Map<String, dynamic>))
          .toList();
      state = state.copyWith(
        items: AsyncValue.data(items),
        unreadCount: items.where((m) => !m.read).length,
      );
    } catch (e, st) {
      state = state.copyWith(items: AsyncValue.error(e, st));
    }
  }

  Future<void> refreshUnreadCount({int days = 30}) async {
    try {
      final resp = await _dio.get('/api/chat/mentions/unread-count',
          queryParameters: {'days': days});
      final inner = _unwrapMap(resp.data);
      final count = (inner['count'] as num?)?.toInt() ?? 0;
      state = state.copyWith(unreadCount: count);
    } catch (_) {/* best-effort */}
  }

  Future<void> markRead(String messageId) async {
    try {
      await _dio.post('/api/chat/mentions/$messageId/read');
      final current = state.items.value ?? const <MentionItem>[];
      final updated = current
          .map((m) => m.messageId == messageId
              ? MentionItem(
                  messageId: m.messageId,
                  chatRoomId: m.chatRoomId,
                  fromUsername: m.fromUsername,
                  contentPreview: m.contentPreview,
                  timestamp: m.timestamp,
                  read: true,
                )
              : m)
          .toList();
      state = state.copyWith(
        items: AsyncValue.data(updated),
        unreadCount: updated.where((m) => !m.read).length,
      );
    } catch (_) {/* best-effort */}
  }

  Future<void> markAllRead({int days = 30}) async {
    try {
      await _dio.post('/api/chat/mentions/read-all', queryParameters: {'days': days});
      await refresh(days: days);
    } catch (_) {/* best-effort */}
  }
}

final mentionsProvider =
    StateNotifierProvider<MentionsNotifier, MentionsState>((ref) {
  final dio = ref.read(dioClientProvider).dio;
  return MentionsNotifier(dio);
});
```

- [ ] **Step 3: Verify**

Run: `cd frontend && flutter analyze lib/shared/models/mention_item.dart lib/features/chat/mentions_provider.dart`
Expected: 0 issues.

- [ ] **Step 4: Commit**

```bash
git add frontend/lib/shared/models/mention_item.dart frontend/lib/features/chat/mentions_provider.dart
git commit -m "feat(frontend): mentions model + provider

StateNotifier with state {items, unreadCount}. Refreshes on construction,
exposes refresh, refreshUnreadCount, markRead, markAllRead. Unwraps
ApiResponse via the same pattern as scheduled_messages_provider.

Confidence: high
Scope-risk: narrow"
```

---

### Task C4: Frontend — Mentions screen + sidebar entry with badge

**Files:**
- Create: `frontend/lib/features/chat/screens/mentions_screen.dart`
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`
- Modify: `frontend/lib/core/routing/app_router.dart`

- [ ] **Step 1: Screen**

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../mentions_provider.dart';

class MentionsScreen extends ConsumerWidget {
  const MentionsScreen({super.key});

  String _format(DateTime dt) {
    final diff = DateTime.now().difference(dt);
    if (diff.inMinutes < 1) return '방금';
    if (diff.inHours < 1) return '${diff.inMinutes}분 전';
    if (diff.inDays < 1) return '${diff.inHours}시간 전';
    if (diff.inDays < 7) return '${diff.inDays}일 전';
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(mentionsProvider);
    final items = state.items;

    return Scaffold(
      appBar: AppBar(
        title: const Text('내 멘션'),
        actions: [
          IconButton(
            icon: const Icon(Icons.done_all),
            tooltip: '모두 읽음',
            onPressed: () =>
                ref.read(mentionsProvider.notifier).markAllRead(),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(mentionsProvider.notifier).refresh(),
          ),
        ],
      ),
      body: items.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('불러오기 실패: $e')),
        data: (list) => list.isEmpty
            ? const Center(child: Text('최근 30일간 멘션이 없습니다.'))
            : ListView.builder(
                itemCount: list.length,
                itemBuilder: (ctx, i) {
                  final m = list[i];
                  return ListTile(
                    leading: CircleAvatar(
                      backgroundColor: m.read
                          ? Colors.grey.shade300
                          : Theme.of(ctx).colorScheme.primary,
                      child: Text(
                        m.fromUsername.isNotEmpty
                            ? m.fromUsername[0].toUpperCase()
                            : '?',
                        style: const TextStyle(color: Colors.white),
                      ),
                    ),
                    title: Text(
                      '${m.fromUsername} → ${m.chatRoomId}',
                      style: TextStyle(
                          fontWeight: m.read ? FontWeight.normal : FontWeight.bold),
                    ),
                    subtitle: Text(m.contentPreview,
                        maxLines: 2, overflow: TextOverflow.ellipsis),
                    trailing: Text(_format(m.when),
                        style: const TextStyle(fontSize: 12)),
                    onTap: () async {
                      await ref.read(mentionsProvider.notifier).markRead(m.messageId);
                      if (!ctx.mounted) return;
                      ctx.go('/chat/${m.chatRoomId}?messageId=${m.messageId}');
                    },
                  );
                },
              ),
      ),
    );
  }
}
```

- [ ] **Step 2: Route**

In `app_router.dart`, add (next to `/scheduled`):

```dart
GoRoute(
  path: '/mentions',
  builder: (context, state) => const MentionsScreen(),
),
```

Add the import.

- [ ] **Step 3: Sidebar entry with unread badge**

In `chat_room_sidebar.dart`, near the scheduled-messages entry from Task B7, add:

```dart
Consumer(builder: (_, ref, __) {
  final unread = ref.watch(mentionsProvider).unreadCount;
  return ListTile(
    leading: const Icon(Icons.alternate_email),
    title: const Text('내 멘션'),
    trailing: unread > 0
        ? CircleAvatar(
            radius: 12,
            backgroundColor: Theme.of(context).colorScheme.error,
            child: Text(
              '$unread',
              style: const TextStyle(fontSize: 11, color: Colors.white),
            ),
          )
        : null,
    onTap: () => context.go('/mentions'),
  );
}),
```

Add the import:

```dart
import '../mentions_provider.dart';
```

- [ ] **Step 4: Verify**

Run: `cd frontend && flutter analyze && flutter test`
Expected: green.

- [ ] **Step 5: Manual smoke test**

- Have user A send a message containing `@<userBUsername>` in a room user B is in.
- Open user B's app → sidebar should show "내 멘션" with badge `1`.
- Click → screen lists the mention. Click the entry → navigates to the room scrolled to the message; entry now shows as read; badge decreases.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/features/chat/screens/mentions_screen.dart frontend/lib/features/chat/widgets/chat_room_sidebar.dart frontend/lib/core/routing/app_router.dart
git commit -m "feat(frontend): mentions screen + sidebar badge

GoRoute /mentions. List view with relative-time, unread emphasis,
'all read' action, single-tap mark-read + navigate to message via
the existing deep-link param.

Confidence: high
Scope-risk: narrow"
```

---

## Final Checklist

- [ ] All 13 tasks committed
- [ ] `./gradlew :common:test :chat-service:test :ai-summary-service:test :search-service:test :gateway-service:test` — all green
- [ ] `cd frontend && flutter test` — all green
- [ ] `cd frontend && flutter analyze` — no new issues vs baseline
- [ ] Manual smoke tests passed for: drag-drop upload (web), schedule send (poll-fired delivery within 30s), mention digest (badge increments + decrements correctly)
- [ ] No tracked secrets, build artifacts, or `.DS_Store`
- [ ] No `Co-Authored-By: Claude` trailers

## Deploy notes

- `chat-service` rebuild required (new entity, service, REST controllers, migration). The Flyway V7 migration runs on the first restart after deploy — verify postgres has not lost connectivity during the upgrade.
- `frontend` rebuild required (web bundle).
- Other services: no rebuild needed (they don't consume the new endpoints).
- Cloudflare cache purge after frontend deploy.
