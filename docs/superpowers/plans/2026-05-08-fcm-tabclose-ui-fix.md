# FCM Tab-Close Cleanup + Sidebar Header UI Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop FCM web pushes when the tab closes (not just on logout), and fix the sidebar header that overflows because too many icons were added on top of each other.

**Architecture:**

- **FCM tab-close (Item 2)** — track `fcmToken → Set<roomId>` in Valkey alongside the existing per-room subscribe/unsubscribe calls. Add `POST /api/fcm/unsubscribe-all` that reads the set and unsubscribes the token from every topic atomically. On the web client, register a `beforeunload` handler that calls this endpoint via `fetch(..., {keepalive: true})` so the request survives the unload (sendBeacon can't carry the JWT Bearer header). Conditional Dart import keeps non-web platforms a no-op.
- **Sidebar header overflow** — the 280px-wide sidebar header currently has 5 utility icons (sort + mention + schedule + DM + new room) plus the app brand. That's ~338px of content. Consolidate the secondary actions (mention, schedule, new DM) into a single overflow `PopupMenuButton` ("⋯"), keeping only **sort** and **new room** as direct icons. The unread mention badge propagates onto the overflow icon when count > 0.

**Tech Stack:**
- Backend: Spring Boot 3.2, `StringRedisTemplate.opsForSet()`, MockMvc, JUnit 5 + Mockito
- Frontend: Flutter 3.22 + Riverpod 2.5 + GoRouter, conditional `dart:html` import (web/stub pattern)

---

## File Structure

**Backend (chat-service):**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/FcmNotificationService.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/service/FcmNotificationServiceTest.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/FcmControllerTest.java`

**Frontend:**
- Create: `frontend/lib/core/services/web_unload_handler.dart` (entry, conditional import dispatcher)
- Create: `frontend/lib/core/services/web_unload_handler_stub.dart` (no-op for non-web)
- Create: `frontend/lib/core/services/web_unload_handler_web.dart` (dart:html beforeunload + fetch keepalive)
- Modify: `frontend/lib/main.dart` (wire handler registration after auth hydrate)
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart` (consolidate `_SidebarHeader` icons)
- Create: `frontend/test/features/chat/widgets/sidebar_header_test.dart`

---

## Task 1: Backend — Track token→rooms in Valkey + add unsubscribeAll service method

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/FcmNotificationService.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/service/FcmNotificationServiceTest.java`

**Why:** Firebase doesn't expose "list all topics this token is subscribed to". To unsubscribe every room on tab-close, we need to remember the mapping ourselves. Valkey set keyed by token is O(1) per add/remove and survives chat-service replica rotation.

- [ ] **Step 1: Write failing service test for tracking + unsubscribeAll**

Create `chat-service/src/test/java/com/chatflow/chat/service/FcmNotificationServiceTest.java`:

```java
package com.chatflow.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmNotificationServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;

    @InjectMocks
    private FcmNotificationService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void subscribeToRoom_adds_room_to_token_set() {
        service.subscribeToRoom("tok-abc", "room-1");
        verify(setOps).add("chatflow:fcm:rooms:tok-abc", "room-1");
    }

    @Test
    void unsubscribeFromRoom_removes_room_from_token_set() {
        service.unsubscribeFromRoom("tok-abc", "room-1");
        verify(setOps).remove("chatflow:fcm:rooms:tok-abc", "room-1");
    }

    @Test
    void unsubscribeAll_with_no_rooms_is_noop() {
        when(setOps.members("chatflow:fcm:rooms:tok-abc")).thenReturn(Set.of());
        service.unsubscribeAll("tok-abc");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void unsubscribeAll_removes_all_rooms_and_deletes_set() {
        when(setOps.members("chatflow:fcm:rooms:tok-abc"))
            .thenReturn(Set.of("room-1", "room-2"));
        service.unsubscribeAll("tok-abc");
        verify(redisTemplate).delete("chatflow:fcm:rooms:tok-abc");
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run: `./gradlew :chat-service:test --tests FcmNotificationServiceTest`
Expected: FAIL — `unsubscribeAll` method doesn't exist; `subscribeToRoom` doesn't touch Redis.

- [ ] **Step 3: Modify FcmNotificationService to track + unsubscribeAll**

Edit `chat-service/src/main/java/com/chatflow/chat/service/FcmNotificationService.java`. Add field, constructor injection, and modify the two existing methods plus add a new one:

```java
import org.springframework.data.redis.core.StringRedisTemplate;
// ...

@Service
public class FcmNotificationService {

    private static final String ROOMS_KEY_PREFIX = "chatflow:fcm:rooms:";

    @Value("${firebase.service-account-path:classpath:firebase-service-account.json}")
    private Resource serviceAccountResource;

    private final StringRedisTemplate redisTemplate;
    private FirebaseMessaging messaging;

    public FcmNotificationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ... init() unchanged ...

    @Async("persistenceExecutor")
    public void subscribeToRoom(String token, String roomId) {
        redisTemplate.opsForSet().add(ROOMS_KEY_PREFIX + token, roomId);
        if (messaging == null) return;
        try {
            messaging.subscribeToTopicAsync(List.of(token), "room-" + roomId);
            log.debug("FCM subscribed token to room-{}", roomId);
        } catch (Exception e) {
            log.warn("FCM subscribe failed: {}", e.getMessage());
        }
    }

    @Async("persistenceExecutor")
    public void unsubscribeFromRoom(String token, String roomId) {
        redisTemplate.opsForSet().remove(ROOMS_KEY_PREFIX + token, roomId);
        if (messaging == null) return;
        try {
            messaging.unsubscribeFromTopicAsync(List.of(token), "room-" + roomId);
            log.debug("FCM unsubscribed token from room-{}", roomId);
        } catch (Exception e) {
            log.warn("FCM unsubscribe failed: {}", e.getMessage());
        }
    }

    /**
     * Removes the token from every room topic it was subscribed to.
     * Used on tab-close so push notifications stop arriving while the user is away.
     */
    public void unsubscribeAll(String token) {
        String key = ROOMS_KEY_PREFIX + token;
        Set<String> rooms = redisTemplate.opsForSet().members(key);
        if (rooms == null || rooms.isEmpty()) return;
        if (messaging != null) {
            for (String roomId : rooms) {
                try {
                    messaging.unsubscribeFromTopicAsync(List.of(token), "room-" + roomId);
                } catch (Exception e) {
                    log.warn("FCM unsubscribe-all (room {}) failed: {}", roomId, e.getMessage());
                }
            }
        }
        redisTemplate.delete(key);
        log.debug("FCM unsubscribed token from {} rooms", rooms.size());
    }

    public boolean isEnabled() {
        return messaging != null;
    }
}
```

Also add `import java.util.Set;` at top if absent.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-service:test --tests FcmNotificationServiceTest`
Expected: PASS — 4/4 tests green.

- [ ] **Step 5: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/FcmNotificationService.java \
        chat-service/src/test/java/com/chatflow/chat/service/FcmNotificationServiceTest.java
git commit -m "$(cat <<'EOF'
feat(chat-service): track FCM token→rooms in Valkey + add unsubscribeAll

Firebase Admin SDK does not expose a "list topics for token" query, so we
mirror the subscribe/unsubscribe calls into a Valkey set keyed by token.
unsubscribeAll(token) reads the set and unsubscribes the token from every
known room topic, then deletes the set.

Constraint: FCM does not provide reverse-lookup from token to topics
Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 2: Backend — POST /api/fcm/unsubscribe-all endpoint

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java`
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/FcmControllerTest.java`

**Why:** The web client needs a single call to clean up everything when the tab closes; we don't want it to enumerate room IDs.

- [ ] **Step 1: Write failing controller test**

Create `chat-service/src/test/java/com/chatflow/chat/controller/FcmControllerTest.java`:

```java
package com.chatflow.chat.controller;

import com.chatflow.chat.service.FcmNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FcmController.class)
@AutoConfigureMockMvc(addFilters = false)
class FcmControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private FcmNotificationService service;

    @Test
    @WithMockUser
    void unsubscribeAll_calls_service() throws Exception {
        String body = objectMapper.writeValueAsString(
            Map.of("token", "x".repeat(120)));
        mockMvc.perform(post("/api/fcm/unsubscribe-all")
                .with(csrf())
                .contentType("application/json")
                .content(body))
            .andExpect(status().isOk());
        verify(service).unsubscribeAll("x".repeat(120));
    }

    @Test
    @WithMockUser
    void unsubscribeAll_rejects_blank_token() throws Exception {
        mockMvc.perform(post("/api/fcm/unsubscribe-all")
                .with(csrf())
                .contentType("application/json")
                .content("{\"token\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-service:test --tests FcmControllerTest`
Expected: FAIL — `/api/fcm/unsubscribe-all` returns 404.

- [ ] **Step 3: Add controller endpoint**

Edit `chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java`. Add a new method and a new request DTO inside the controller class:

```java
@PostMapping("/unsubscribe-all")
public ResponseEntity<ApiResponse<Void>> unsubscribeAll(@Valid @RequestBody UnsubscribeAllRequest req) {
    fcmNotificationService.unsubscribeAll(req.getToken());
    return ResponseEntity.ok(ApiResponse.ok(null));
}

@Data
public static class UnsubscribeAllRequest {
    @NotBlank @Size(min = 100, max = 300) private String token;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-service:test --tests FcmControllerTest`
Expected: PASS — 2/2 tests green.

- [ ] **Step 5: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java \
        chat-service/src/test/java/com/chatflow/chat/controller/FcmControllerTest.java
git commit -m "$(cat <<'EOF'
feat(chat-service): POST /api/fcm/unsubscribe-all

Single endpoint to detach an FCM token from every room topic it was
subscribed to. Backed by FcmNotificationService.unsubscribeAll which
reads the Valkey set populated by subscribe/unsubscribe.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Task 3: Frontend — beforeunload handler with conditional Dart import

**Files:**
- Create: `frontend/lib/core/services/web_unload_handler.dart`
- Create: `frontend/lib/core/services/web_unload_handler_stub.dart`
- Create: `frontend/lib/core/services/web_unload_handler_web.dart`
- Modify: `frontend/lib/main.dart`

**Why:** When the user closes the tab without logging out, the FCM token stays subscribed. The next push wakes the device unnecessarily. `beforeunload` lets us fire one cleanup call before the tab dies. `fetch(..., {keepalive: true})` is the only browser primitive that allows custom headers (Authorization) on a request that outlives the page — `navigator.sendBeacon` cannot send the JWT.

- [ ] **Step 1: Create the conditional-import entry**

Create `frontend/lib/core/services/web_unload_handler.dart`:

```dart
// Conditional import: web → web_unload_handler_web.dart, otherwise stub.
export 'web_unload_handler_stub.dart'
    if (dart.library.html) 'web_unload_handler_web.dart';
```

- [ ] **Step 2: Create the non-web stub**

Create `frontend/lib/core/services/web_unload_handler_stub.dart`:

```dart
/// No-op on platforms without `dart:html` (mobile, desktop). Native FCM
/// lifecycle is handled by the OS — beforeunload only matters on the web.
class WebUnloadHandler {
  static void register({
    required String Function() jwtProvider,
    required Future<String?> Function() fcmTokenProvider,
    required String apiBaseUrl,
  }) {
    // intentionally empty
  }
}
```

- [ ] **Step 3: Create the web implementation**

Create `frontend/lib/core/services/web_unload_handler_web.dart`:

```dart
// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;
import 'dart:convert';

class WebUnloadHandler {
  static bool _registered = false;

  static void register({
    required String Function() jwtProvider,
    required Future<String?> Function() fcmTokenProvider,
    required String apiBaseUrl,
  }) {
    if (_registered) return;
    _registered = true;

    String? cachedFcmToken;
    fcmTokenProvider().then((t) => cachedFcmToken = t);

    html.window.onBeforeUnload.listen((_) {
      final jwt = jwtProvider();
      final fcm = cachedFcmToken;
      if (jwt.isEmpty || fcm == null || fcm.isEmpty) return;
      // fetch + keepalive: the browser is allowed to finish this in-flight
      // even after the document is gone. sendBeacon cannot carry the JWT
      // header so it isn't usable here.
      html.window.fetch(
        '$apiBaseUrl/api/fcm/unsubscribe-all',
        {
          'method': 'POST',
          'keepalive': true,
          'headers': {
            'Authorization': 'Bearer $jwt',
            'Content-Type': 'application/json',
          },
          'body': jsonEncode({'token': fcm}),
        }.jsify(),
      );
    });
  }
}
```

(Note: `html.window.fetch` is exposed via `dart:html`. If the analyzer complains about `.jsify()` import, add `import 'dart:js_util' as js_util;` and call `js_util.jsify({...})` instead.)

- [ ] **Step 4: Wire registration in main.dart**

Open `frontend/lib/main.dart`. Locate the place where the auth provider has hydrated and the app is about to run (search for `runApp(`). Just before `runApp(`, register the handler:

```dart
import 'core/services/web_unload_handler.dart';
import 'core/services/fcm_service.dart';
import 'core/network/dio_client.dart'; // for apiBaseUrl

// ...

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp(/* existing args */);
  await FcmService.initialize();

  final container = ProviderContainer();

  WebUnloadHandler.register(
    jwtProvider: () => container.read(authProvider).token ?? '',
    fcmTokenProvider: FcmService.getToken,
    apiBaseUrl: const String.fromEnvironment('API_BASE_URL', defaultValue: ''),
  );

  runApp(UncontrolledProviderScope(
    container: container,
    child: const ChatFlowApp(),
  ));
}
```

(Adjust to match the existing `main.dart` shape — if it already uses `ProviderScope`, replace with `UncontrolledProviderScope` so the same container is shared.)

- [ ] **Step 5: Manual smoke test (no automated test — beforeunload can't be unit-tested)**

```bash
cd frontend && flutter run -d chrome
```

In a logged-in tab:
1. Join 2 rooms.
2. Open DevTools → Network tab.
3. Close the tab.
4. The Network panel should show `POST /api/fcm/unsubscribe-all` with status 200 (it survives tab close due to `keepalive`).
5. From a second device that posts a message to either room — push should NOT arrive on the closed tab's machine.

Document the result in the commit message.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/core/services/web_unload_handler.dart \
        frontend/lib/core/services/web_unload_handler_stub.dart \
        frontend/lib/core/services/web_unload_handler_web.dart \
        frontend/lib/main.dart
git commit -m "$(cat <<'EOF'
feat(frontend): unsubscribe FCM topics on tab close (web)

beforeunload listener fires fetch(..., {keepalive: true}) to
/api/fcm/unsubscribe-all so push notifications stop arriving while
the user has the tab closed but is still logged in.

Conditional import keeps mobile/desktop a no-op (native FCM lifecycle
is OS-managed there).

Constraint: navigator.sendBeacon cannot send the Authorization header
Rejected: deleting the FCM token client-side | next session would need
  a brand-new token registration round-trip
Confidence: medium
Scope-risk: narrow
Not-tested: cross-browser beforeunload reliability (Safari throttles)
EOF
)"
```

---

## Task 4: Frontend — Sidebar header icon consolidation

**Files:**
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart` (around lines 599–795, the `_SidebarHeader` widget)
- Create: `frontend/test/features/chat/widgets/sidebar_header_test.dart`

**Why:** The 280px sidebar header currently lays out: app icon + "ChatFlow" text (~128px) + sort + mention + schedule + DM + new room (~184px) = ~312px of content + 32px padding = 344px. Result: icons get clipped or wrap. We keep **sort + new room** as direct icons (most-used) and move **mention + schedule + DM** into a single overflow `PopupMenuButton`. The overflow icon shows a red dot when the mention unread count > 0 so the visual signal is preserved.

- [ ] **Step 1: Write a failing widget test**

Create `frontend/test/features/chat/widgets/sidebar_header_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:chatflow/features/chat/widgets/chat_room_sidebar.dart'
    show ChatRoomSidebar;
import 'package:chatflow/features/mentions/mentions_provider.dart';
import 'package:chatflow/shared/models/mention_item.dart';

class _FakeMentionsState extends MentionsState {
  _FakeMentionsState(int count)
      : super(items: const <MentionItem>[], unreadCount: count, loading: false);
}

void main() {
  testWidgets('sidebar header fits within 280px without overflow', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mentionsProvider.overrideWith((ref) => _FakeMentionsNotifier(0)),
        ],
        child: const MaterialApp(
          home: Scaffold(body: ChatRoomSidebar()),
        ),
      ),
    );
    // No "RenderFlex overflowed" error means the test passes.
    expect(tester.takeException(), isNull);
  });

  testWidgets('overflow icon shows red dot when mentions unread > 0',
      (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mentionsProvider.overrideWith((ref) => _FakeMentionsNotifier(3)),
        ],
        child: const MaterialApp(
          home: Scaffold(body: ChatRoomSidebar()),
        ),
      ),
    );
    expect(find.byKey(const Key('sidebar-header-more-badge')), findsOneWidget);
  });
}

class _FakeMentionsNotifier extends MentionsNotifier {
  _FakeMentionsNotifier(int count)
      : super.test(_FakeMentionsState(count));
}
```

(If `MentionsNotifier.test` constructor doesn't exist, add a `@visibleForTesting` factory: `MentionsNotifier.test(MentionsState s) : super(s);`. Or use the simpler approach — override the provider with `Provider<MentionsState>` if the rest of the codebase exposes a state-only provider.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && flutter test test/features/chat/widgets/sidebar_header_test.dart`
Expected: FAIL — overflow error from RenderFlex (5 icons > 280px) AND no key `sidebar-header-more-badge` in the tree.

- [ ] **Step 3: Refactor `_SidebarHeader` to a 3-icon layout**

Edit `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`. Replace the `_SidebarHeader.build` Row children (currently 5 trailing icons after the `Spacer()`) with this layout — keep sort first, then a "more" `PopupMenuButton` consolidating mention/schedule/new-DM, then "new room":

```dart
final Spacer(),
if (onSortSelected != null)
  PopupMenuButton<RoomSortOption>(
    icon: Icon(Icons.sort, size: 18, color: cs.onSurfaceVariant),
    tooltip: '정렬',
    padding: EdgeInsets.zero,
    constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
    onSelected: onSortSelected,
    itemBuilder: (ctx) => [
      _sortItem(cs, RoomSortOption.recent, '최근 메시지 순'),
      _sortItem(cs, RoomSortOption.unread, '미읽음 많은 순'),
      _sortItem(cs, RoomSortOption.name, '이름 순'),
    ],
  ),
const SizedBox(width: 6),
Consumer(builder: (_, ref, __) {
  final unread = ref.watch(mentionsProvider).unreadCount;
  return PopupMenuButton<String>(
    tooltip: '더 보기',
    padding: EdgeInsets.zero,
    constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
    icon: Stack(
      clipBehavior: Clip.none,
      children: [
        Icon(Icons.more_horiz, size: 20, color: cs.onSurfaceVariant),
        if (unread > 0)
          Positioned(
            key: const Key('sidebar-header-more-badge'),
            right: -2,
            top: -2,
            child: Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                color: cs.error,
                shape: BoxShape.circle,
                border: Border.all(color: cs.surface, width: 1),
              ),
            ),
          ),
      ],
    ),
    onSelected: (value) {
      if (value == 'mentions') context.go('/mentions');
      else if (value == 'scheduled') context.go('/scheduled');
      else if (value == 'dm' && onDmTap != null) onDmTap!();
    },
    itemBuilder: (_) => [
      PopupMenuItem(
        value: 'mentions',
        child: Row(children: [
          const Icon(Icons.alternate_email, size: 18),
          const SizedBox(width: 8),
          Expanded(child: Text(unread > 0 ? '내 멘션 ($unread)' : '내 멘션')),
        ]),
      ),
      const PopupMenuItem(
        value: 'scheduled',
        child: Row(children: [
          Icon(Icons.schedule_send_outlined, size: 18),
          SizedBox(width: 8),
          Text('예약된 메시지'),
        ]),
      ),
      if (onDmTap != null)
        const PopupMenuItem(
          value: 'dm',
          child: Row(children: [
            Icon(Icons.person_add_outlined, size: 18),
            SizedBox(width: 8),
            Text('새 DM'),
          ]),
        ),
    ],
  );
}),
const SizedBox(width: 6),
Tooltip(
  message: '새 채팅방',
  child: InkWell(
    onTap: onCreateTap,
    borderRadius: BorderRadius.circular(8),
    child: Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        color: cs.surfaceContainer,
        border: Border.all(color: cs.outline.withAlpha(80)),
      ),
      child: Icon(Icons.edit_outlined,
          size: 16, color: cs.onSurfaceVariant),
    ),
  ),
),
```

Add a helper method on `_SidebarHeader` to avoid duplicating the sort PopupMenuItem code:

```dart
PopupMenuItem<RoomSortOption> _sortItem(
    ColorScheme cs, RoomSortOption value, String label) {
  return PopupMenuItem(
    value: value,
    child: Row(children: [
      if (currentSort == value)
        Icon(Icons.check, size: 16, color: cs.primary)
      else
        const SizedBox(width: 16),
      const SizedBox(width: 8),
      Text(label),
    ]),
  );
}
```

Width math after fix: 32 (app icon) + 10 (gap) + ~80 (text) = 122px on the left.
Right: 32 (sort) + 6 + 32 (more) + 6 + 32 (new room) = 108px.
Total: 122 + 108 + 32 (h-padding) = **262px**. Fits in 280px with 18px slack.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && flutter test test/features/chat/widgets/sidebar_header_test.dart`
Expected: PASS — no overflow, badge key found when unread > 0.

- [ ] **Step 5: Run wider analyzer + format check**

Run: `cd frontend && flutter analyze lib/features/chat/widgets/chat_room_sidebar.dart`
Expected: no errors, no new warnings.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/features/chat/widgets/chat_room_sidebar.dart \
        frontend/test/features/chat/widgets/sidebar_header_test.dart
git commit -m "$(cat <<'EOF'
fix(frontend): sidebar header icon overflow — consolidate into 3 slots

The sidebar header had 5 trailing icons (sort/mention/schedule/DM/new-room)
which totaled ~184px in a 280px container, pushing the brand text and
clipping icons. Keep sort + new-room as direct icons. Move
mention/schedule/new-DM into a single "more" PopupMenuButton with a
red-dot badge when mention unread > 0.

Confidence: high
Scope-risk: narrow
Directive: When adding new sidebar utility actions, prefer the "more"
  popup menu over a new direct icon — the header has no slack left
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- Item 2 backend → Tasks 1+2.
- Item 2 frontend → Task 3.
- UI fix → Task 4.

**Placeholder scan:** None — every step has full code, file paths, and exact commands.

**Type consistency:**
- `unsubscribeAll(String token)` defined in Task 1, called in Task 2's controller, no signature drift.
- `WebUnloadHandler.register(...)` signature identical in Tasks 3.1 (entry), 3.2 (stub), 3.3 (web).
- `mentionsProvider`/`MentionsState`/`MentionsNotifier` referenced in Task 4 — verified to exist by grep earlier in the session.

---

## Execution

Use **superpowers:subagent-driven-development** with this plan file. Tasks are independent enough to be dispatched sequentially with two-stage review per task.

Branch: `feature/fcm-tabclose-ui-fix` (already created).
