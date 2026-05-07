# Smart Reply Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show 3 short Gemini-generated reply suggestions as chips above the chat input whenever a non-self message arrives. Tap a chip to fill the input; user reviews + sends manually.

**Architecture:**
- **Backend (ai-summary-service):** New endpoint `POST /api/ai-summary/quick-replies` reads the last 10 messages from the existing Redis buffer (`chatflow:summary:buffer:<roomId>`) **without consuming**, builds a Gemini prompt, parses the JSON-array response into 3 short replies. Reuses the existing `chatModelClient.generate()` and `rateLimiter` from `AiSummaryService`. Caches per `(roomId, latestMessageId)` for 30 minutes — the same conversation context never burns more than one Gemini call.
- **Frontend:** New `quickReplyProvider(roomId)` (StateNotifier family). `chat_notifier` triggers a debounced refresh (1s) on every incoming non-self message. New `QuickReplyChips` widget renders horizontally above the input; tap fills `_controller.text`. No automatic send.

**Tech Stack:** Spring Boot 3.2 + LangChain4J + Gemini 1.5 Flash (existing), Flutter Riverpod 2.5 + Dio (existing).

---

## File Structure

| File | Action | Phase |
|------|--------|-------|
| `ai-summary-service/src/main/java/com/chatflow/aisummary/dto/QuickReplyResponse.java` | Create | A |
| `ai-summary-service/src/main/java/com/chatflow/aisummary/service/QuickReplyService.java` | Create | A |
| `ai-summary-service/src/test/java/com/chatflow/aisummary/service/QuickReplyServiceTest.java` | Create | A |
| `ai-summary-service/src/main/java/com/chatflow/aisummary/controller/AiSummaryController.java` | Modify (add endpoint) | A |
| `frontend/lib/shared/models/quick_reply.dart` | Create | B |
| `frontend/lib/features/chat/quick_reply_provider.dart` | Create | B |
| `frontend/lib/features/chat/widgets/quick_reply_chips.dart` | Create | B |
| `frontend/lib/features/chat/widgets/chat_input.dart` | Modify (render chips above input) | B |
| `frontend/lib/features/chat/chat_notifier.dart` | Modify (debounce trigger on incoming non-self) | B |
| `frontend/lib/features/chat/chat_page.dart` | Modify (wire callback) | B |
| `frontend/test/shared/models/quick_reply_test.dart` | Create | B |

---

## Phase A — Backend (ai-summary-service)

### Task A1: `QuickReplyService` with TDD

**Why:** Centralize the Smart Reply logic in a dedicated service. Reuse `chatModelClient`, `rateLimiter`, and the `redisTemplate` already wired into `AiSummaryService`. The service is its own bean so unit tests can mock dependencies cleanly.

**Files:**
- Create: `ai-summary-service/src/main/java/com/chatflow/aisummary/dto/QuickReplyResponse.java`
- Create: `ai-summary-service/src/main/java/com/chatflow/aisummary/service/QuickReplyService.java`
- Create: `ai-summary-service/src/test/java/com/chatflow/aisummary/service/QuickReplyServiceTest.java`

#### Step 1: DTO

```java
package com.chatflow.aisummary.dto;

import java.util.List;

public record QuickReplyResponse(List<String> suggestions) {}
```

#### Step 2: Failing test FIRST

```java
package com.chatflow.aisummary.service;

import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickReplyServiceTest {

    @Mock private ChatLanguageModel chatModelClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private ValueOperations<String, String> valueOps;

    private QuickReplyService service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        service = new QuickReplyService(chatModelClient, redisTemplate, objectMapper);
    }

    private String json(String content, String username) throws Exception {
        ChatMessage m = new ChatMessage();
        m.setMessageId("m-" + content.hashCode());
        m.setChatRoomId("room-1");
        m.setUsername(username);
        m.setUserId("u-" + username);
        m.setContent(content);
        m.setTimestamp(LocalDateTime.now());
        m.setType(ChatMessage.MessageType.CHAT);
        return objectMapper.writeValueAsString(m);
    }

    @Test
    void generateQuickReplies_returnsParsedSuggestions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null); // cache miss
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(eq("chatflow:summary:buffer:room-1"), eq(0L), eq(-1L)))
                .thenReturn(List.of(
                        json("회의 시작했어요", "alice"),
                        json("어디 회의실이에요?", "bob")));
        when(chatModelClient.generate(anyString()))
                .thenReturn("[\"3층 회의실이요\", \"잠시만요 확인할게요\", \"바로 갈게요\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-12345");

        assertThat(result.suggestions()).containsExactly(
                "3층 회의실이요", "잠시만요 확인할게요", "바로 갈게요");
        // Cache write: setIfAbsent or set with TTL
        verify(valueOps).set(eq("chatflow:smart-reply:room-1:m-12345"),
                anyString(), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void generateQuickReplies_servesFromCacheWhenPresent() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chatflow:smart-reply:room-1:m-cached"))
                .thenReturn("[\"안녕하세요\", \"네 알겠습니다\", \"확인했어요\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-cached");

        assertThat(result.suggestions()).containsExactly(
                "안녕하세요", "네 알겠습니다", "확인했어요");
        verify(chatModelClient, never()).generate(anyString());
    }

    @Test
    void generateQuickReplies_returnsEmptyWhenBufferEmpty() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-xx");

        assertThat(result.suggestions()).isEmpty();
        verify(chatModelClient, never()).generate(anyString());
    }

    @Test
    void generateQuickReplies_returnsEmptyOnGeminiMalformedResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(json("hi", "bob")));
        when(chatModelClient.generate(anyString()))
                .thenReturn("garbage not-json output");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-x");

        assertThat(result.suggestions()).isEmpty();
        // Cache should NOT be written for malformed responses
        verify(valueOps, never()).set(anyString(), anyString(), any(Long.class), any(TimeUnit.class));
    }

    @Test
    void generateQuickReplies_truncatesMoreThan3Suggestions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(json("hi", "bob")));
        when(chatModelClient.generate(anyString()))
                .thenReturn("[\"a\", \"b\", \"c\", \"d\", \"e\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-x");

        assertThat(result.suggestions()).containsExactly("a", "b", "c");
    }

    @Test
    void generateQuickReplies_filtersEmptyAndOverlongSuggestions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(json("hi", "bob")));
        // Suggestions: empty + 200-char + valid + valid
        String overlong = "x".repeat(200);
        when(chatModelClient.generate(anyString()))
                .thenReturn("[\"\", \"" + overlong + "\", \"valid1\", \"valid2\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-x");

        assertThat(result.suggestions()).containsExactly("valid1", "valid2");
    }
}
```

Run: `./gradlew :ai-summary-service:test --tests com.chatflow.aisummary.service.QuickReplyServiceTest`
Expected: COMPILE FAIL (`QuickReplyService` doesn't exist).

#### Step 3: Implement the service

```java
package com.chatflow.aisummary.service;

import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QuickReplyService {

    private final ChatLanguageModel chatModelClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String BUFFER_KEY_PREFIX = "chatflow:summary:buffer:";
    private static final String CACHE_KEY_PREFIX = "chatflow:smart-reply:";
    private static final long CACHE_TTL_MINUTES = 30L;
    private static final int MAX_CONTEXT_MESSAGES = 10;
    private static final int MAX_SUGGESTION_LENGTH = 60; // chars
    private static final int TARGET_SUGGESTION_COUNT = 3;

    @Autowired
    public QuickReplyService(
            ChatLanguageModel chatModelClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.chatModelClient = chatModelClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public QuickReplyResponse generateQuickReplies(String roomId, String latestMessageId) {
        // 1. Cache lookup
        String cacheKey = CACHE_KEY_PREFIX + roomId + ":" + latestMessageId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                List<String> suggestions = objectMapper.readValue(
                        cached, new TypeReference<List<String>>() {});
                return new QuickReplyResponse(suggestions);
            } catch (Exception e) {
                log.warn("Smart-reply cache poisoned at {} — refetching", cacheKey);
            }
        }

        // 2. Read recent messages from the existing summary buffer (peek, no pop)
        String bufferKey = BUFFER_KEY_PREFIX + roomId;
        List<String> raw = redisTemplate.opsForList().range(bufferKey, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new QuickReplyResponse(List.of());
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (String json : raw) {
            try {
                messages.add(objectMapper.readValue(json, ChatMessage.class));
            } catch (Exception e) {
                // Skip malformed entries
            }
        }
        if (messages.isEmpty()) {
            return new QuickReplyResponse(List.of());
        }

        // Take the last N
        List<ChatMessage> context = messages.size() > MAX_CONTEXT_MESSAGES
                ? messages.subList(messages.size() - MAX_CONTEXT_MESSAGES, messages.size())
                : messages;

        // 3. Build prompt
        String prompt = buildPrompt(context);

        // 4. Call Gemini
        String raw_response;
        try {
            raw_response = chatModelClient.generate(prompt);
        } catch (Exception e) {
            log.warn("Gemini call failed for quick-reply on room {}: {}", roomId, e.getMessage());
            return new QuickReplyResponse(List.of());
        }

        // 5. Parse JSON array
        List<String> suggestions = parseSuggestions(raw_response);
        if (suggestions.isEmpty()) {
            // Don't cache malformed responses
            return new QuickReplyResponse(List.of());
        }

        // 6. Cache (only valid responses)
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(suggestions),
                    CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Failed to cache quick-reply for {}: {}", cacheKey, e.getMessage());
        }

        return new QuickReplyResponse(suggestions);
    }

    private String buildPrompt(List<ChatMessage> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 채팅방의 최근 대화입니다:\n");
        for (ChatMessage m : context) {
            sb.append(m.getUsername()).append(": ").append(m.getContent()).append('\n');
        }
        sb.append("\n마지막 메시지에 대한 자연스러운 짧은 답장 후보 3개를 생성하세요.\n");
        sb.append("각 답장은 30자 이내, 한국어로, JSON 배열로만 응답:\n");
        sb.append("[\"답장1\", \"답장2\", \"답장3\"]\n");
        return sb.toString();
    }

    private List<String> parseSuggestions(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return List.of();
        // Gemini occasionally wraps the array in ```json fences — strip them first
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        try {
            List<String> raw = objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
            return raw.stream()
                    .filter(s -> s != null && !s.isBlank() && s.length() <= MAX_SUGGESTION_LENGTH)
                    .limit(TARGET_SUGGESTION_COUNT)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Smart-reply parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
```

#### Step 4: Re-run targeted tests + full suite

Run:
```
./gradlew :ai-summary-service:test --tests com.chatflow.aisummary.service.QuickReplyServiceTest
./gradlew :ai-summary-service:test
```
Expected: 6/6 new tests pass + full suite green.

#### Step 5: Commit

```bash
git add ai-summary-service/src/main/java/com/chatflow/aisummary/dto/QuickReplyResponse.java \
        ai-summary-service/src/main/java/com/chatflow/aisummary/service/QuickReplyService.java \
        ai-summary-service/src/test/java/com/chatflow/aisummary/service/QuickReplyServiceTest.java
git commit -m "$(cat <<'EOF'
feat(ai-summary): QuickReplyService with TDD

Reads the last 10 messages from the existing summary Redis buffer
(non-destructive range, not pop) — same buffer the summary trigger
uses, so we get fresh context for free without a separate ingestion
pipeline.

Caches per (roomId, latestMessageId) for 30 minutes — same
conversation never burns more than one Gemini call. Cache miss
on malformed Gemini output (don't poison).

Output validation: filters empty + over-60-char strings, limits to
top 3. Strips ```json``` fences Gemini occasionally adds.

6 unit tests cover: cache hit, cache miss + Gemini call, empty
buffer, malformed Gemini response, more-than-3 truncation,
filter empty/overlong.

Confidence: high
Scope-risk: narrow — additive bean; existing AiSummaryService
  unaffected
Directive: do NOT consume the buffer (rightPop / trim) here — the
  summary trigger owns that lifecycle. range() is read-only.
EOF
)"
```

---

### Task A2: REST endpoint

**Files:**
- Modify: `ai-summary-service/src/main/java/com/chatflow/aisummary/controller/AiSummaryController.java`

#### Step 1: Add the endpoint

Read the controller. Inject `QuickReplyService` (use `@RequiredArgsConstructor`-style or constructor — match existing pattern). Add:

```java
@PostMapping("/quick-replies")
public ResponseEntity<ApiResponse<QuickReplyResponse>> quickReplies(@RequestBody Map<String, String> body) {
    String roomId = body.get("chatRoomId");
    String latestMessageId = body.get("latestMessageId");
    if (roomId == null || roomId.isBlank()) {
        return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId is required"));
    }
    if (latestMessageId == null || latestMessageId.isBlank()) {
        return ResponseEntity.badRequest().body(ApiResponse.error("latestMessageId is required"));
    }
    QuickReplyResponse result = quickReplyService.generateQuickReplies(roomId, latestMessageId);
    return ResponseEntity.ok(ApiResponse.ok(result));
}
```

Add the import:
```java
import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.aisummary.service.QuickReplyService;
```

If the controller uses `@RequiredArgsConstructor`, just add `private final QuickReplyService quickReplyService;` field. Otherwise add to existing constructor.

#### Step 2: Compile + test

Run:
```
./gradlew :ai-summary-service:build
```
Expected: BUILD SUCCESSFUL.

#### Step 3: Commit

```bash
git add ai-summary-service/src/main/java/com/chatflow/aisummary/controller/AiSummaryController.java
git commit -m "$(cat <<'EOF'
feat(ai-summary): POST /api/ai-summary/quick-replies endpoint

Body: {chatRoomId, latestMessageId}
Response: ApiResponse<QuickReplyResponse{suggestions: [str, str, str]}>

Validates non-blank inputs; 400 with ApiResponse.error otherwise.
Delegates to QuickReplyService for cache + Gemini call.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Phase B — Frontend

### Task B1: `QuickReply` model + `quickReplyProvider` family

**Files:**
- Create: `frontend/lib/shared/models/quick_reply.dart`
- Create: `frontend/lib/features/chat/quick_reply_provider.dart`
- Create: `frontend/test/shared/models/quick_reply_test.dart`

#### Step 1: Model

```dart
class QuickReplySuggestions {
  final List<String> suggestions;
  final String latestMessageId;

  const QuickReplySuggestions({
    required this.suggestions,
    required this.latestMessageId,
  });

  factory QuickReplySuggestions.fromJson(
      Map<String, dynamic> json, String latestMessageId) {
    final raw = json['suggestions'];
    final list = raw is List
        ? raw.map((e) => e?.toString() ?? '').where((s) => s.isNotEmpty).toList()
        : <String>[];
    return QuickReplySuggestions(
      suggestions: list,
      latestMessageId: latestMessageId,
    );
  }

  static const empty = QuickReplySuggestions(suggestions: [], latestMessageId: '');

  bool get isEmpty => suggestions.isEmpty;
}
```

#### Step 2: Provider

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/quick_reply.dart';

class QuickReplyNotifier extends StateNotifier<QuickReplySuggestions> {
  final dynamic _dio;
  final String _roomId;
  String? _lastFetchedFor;

  QuickReplyNotifier(this._dio, this._roomId)
      : super(QuickReplySuggestions.empty);

  /// Refresh suggestions for the given latest message. No-op if the same
  /// messageId was already fetched (caller debounces but we de-dupe too).
  Future<void> refresh(String latestMessageId) async {
    if (latestMessageId.isEmpty) return;
    if (_lastFetchedFor == latestMessageId) return;
    _lastFetchedFor = latestMessageId;
    try {
      final resp = await _dio.post(
        '/api/ai-summary/quick-replies',
        data: {'chatRoomId': _roomId, 'latestMessageId': latestMessageId},
      );
      final data = resp.data;
      Map<String, dynamic>? inner;
      if (data is Map && data['data'] is Map) {
        inner = (data['data'] as Map).cast<String, dynamic>();
      } else if (data is Map) {
        inner = data.cast<String, dynamic>();
      }
      if (inner == null) return;
      state = QuickReplySuggestions.fromJson(inner, latestMessageId);
    } catch (_) {
      // Best-effort feature; do not surface errors.
    }
  }

  void clear() {
    _lastFetchedFor = null;
    state = QuickReplySuggestions.empty;
  }
}

final quickReplyProvider =
    StateNotifierProvider.family<QuickReplyNotifier, QuickReplySuggestions, String>(
        (ref, roomId) {
  final dio = ref.read(dioClientProvider).dio;
  return QuickReplyNotifier(dio, roomId);
});
```

#### Step 3: Test

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/quick_reply.dart';

void main() {
  group('QuickReplySuggestions.fromJson', () {
    test('parses canonical backend shape', () {
      final json = {'suggestions': ['hi', 'sure', 'on it']};
      final s = QuickReplySuggestions.fromJson(json, 'm-1');
      expect(s.suggestions, ['hi', 'sure', 'on it']);
      expect(s.latestMessageId, 'm-1');
      expect(s.isEmpty, false);
    });

    test('filters empty strings', () {
      final json = {'suggestions': ['hi', '', 'ok', null]};
      final s = QuickReplySuggestions.fromJson(json, 'm-2');
      expect(s.suggestions, ['hi', 'ok']);
    });

    test('returns empty on missing or wrong-shape suggestions', () {
      expect(QuickReplySuggestions.fromJson({}, 'm').suggestions, isEmpty);
      expect(QuickReplySuggestions.fromJson(
          {'suggestions': 'not-a-list'}, 'm').suggestions, isEmpty);
    });

    test('isEmpty reflects suggestions', () {
      expect(QuickReplySuggestions.empty.isEmpty, true);
      final populated = QuickReplySuggestions.fromJson(
          {'suggestions': ['x']}, 'm');
      expect(populated.isEmpty, false);
    });
  });
}
```

#### Step 4: Verify + commit

Run:
```
cd frontend
flutter test test/shared/models/quick_reply_test.dart
flutter test
flutter analyze lib/shared/models/quick_reply.dart lib/features/chat/quick_reply_provider.dart test/shared/models/quick_reply_test.dart
```
Expected: 4/4 new tests pass; full suite green; 0 new analyze issues.

```bash
git add frontend/lib/shared/models/quick_reply.dart \
        frontend/lib/features/chat/quick_reply_provider.dart \
        frontend/test/shared/models/quick_reply_test.dart
git commit -m "$(cat <<'EOF'
feat(frontend): QuickReply model + per-room provider family

StateNotifier.family keyed by roomId. de-dupes consecutive refresh
calls for the same messageId. Best-effort: errors are swallowed
(feature is optional UX, never blocks chat).

4 unit tests on the model fromJson — covers canonical, empty filter,
wrong-shape fallback, isEmpty.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

### Task B2: `QuickReplyChips` widget

**Files:**
- Create: `frontend/lib/features/chat/widgets/quick_reply_chips.dart`

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../quick_reply_provider.dart';

class QuickReplyChips extends ConsumerWidget {
  final String roomId;
  final void Function(String suggestion) onTap;

  const QuickReplyChips({
    super.key,
    required this.roomId,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(quickReplyProvider(roomId));
    if (state.isEmpty) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;
    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        itemCount: state.suggestions.length,
        separatorBuilder: (_, __) => const SizedBox(width: 6),
        itemBuilder: (ctx, i) {
          final text = state.suggestions[i];
          return ActionChip(
            label: Text(text, style: const TextStyle(fontSize: 13)),
            backgroundColor: cs.surfaceContainer,
            side: BorderSide(color: cs.outline.withAlpha(80)),
            visualDensity: VisualDensity.compact,
            onPressed: () => onTap(text),
          );
        },
      ),
    );
  }
}
```

Verify + commit:
```
cd frontend
flutter analyze lib/features/chat/widgets/quick_reply_chips.dart
flutter test
```
Expected: 0 issues; tests still pass.

```bash
git add frontend/lib/features/chat/widgets/quick_reply_chips.dart
git commit -m "$(cat <<'EOF'
feat(frontend): QuickReplyChips widget — horizontal-scroll suggestion chips

ConsumerWidget watches quickReplyProvider(roomId). Returns SizedBox.shrink
when empty (zero space when no suggestions). ActionChip per suggestion;
tap fires onTap callback. 40-px row height matches the project's other
input-adjacent chip rows.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

### Task B3: Wire into `chat_input` + `chat_notifier`

**Files:**
- Modify: `frontend/lib/features/chat/widgets/chat_input.dart` — render `QuickReplyChips` above the input row
- Modify: `frontend/lib/features/chat/chat_notifier.dart` — debounced refresh on incoming non-self message
- Modify: `frontend/lib/features/chat/chat_page.dart` — pass `roomId` to ChatInput's quick-reply prop (likely already in scope)

#### Step 1: ChatInput rendering

Read `chat_input.dart`. Find the column that contains the input row (the `Container` containing the actual `TextField` + send button row). Just above that row, render:

```dart
QuickReplyChips(
  roomId: widget.roomId,
  onTap: (suggestion) {
    _controller.text = suggestion;
    _controller.selection = TextSelection.collapsed(offset: suggestion.length);
    _focusNode.requestFocus();
  },
),
```

Add the import:
```dart
import 'quick_reply_chips.dart';
```

> The `widget.roomId` field — verify it exists. If not, add it as a constructor param. Most ChatInput callers already pass roomId for other purposes (file pick callback receives it indirectly). If needed: add `final String roomId;` to ChatInput, require it in the constructor, and pass it from chat_page.

#### Step 2: ChatNotifier debounced trigger

In `chat_notifier.dart`, locate `_onMessage(ChatMessage msg)`. Add a debounce field:

```dart
Timer? _quickReplyDebounce;
```

In `_onMessage`, AFTER the message is appended to state, add:

```dart
// Smart Reply: refresh suggestions when a non-self message arrives.
if (msg.userId != _userId && _currentRoomId != null) {
  _quickReplyDebounce?.cancel();
  _quickReplyDebounce = Timer(const Duration(seconds: 1), () {
    if (!mounted || _currentRoomId == null) return;
    final id = msg.messageId ?? msg.localId ?? '';
    if (id.isEmpty) return;
    _ref.read(quickReplyProvider(_currentRoomId!).notifier).refresh(id);
  });
}
```

Add the import:
```dart
import 'quick_reply_provider.dart';
```

In `dispose()` (or wherever the notifier cleans up), cancel the timer:
```dart
_quickReplyDebounce?.cancel();
```

> The exact field names (`_userId`, `_currentRoomId`, `_ref`) should already exist — verify by reading the file. The `_ref` is the Riverpod ref the notifier holds; if accessed differently in this codebase, adapt accordingly.

#### Step 3: chat_page.dart pass roomId

If ChatInput now requires `roomId`, pass it from chat_page.dart at the construction site. `widget.roomId` is already in scope.

#### Step 4: Verify + commit

```
cd frontend
flutter analyze
flutter test
```
Expected: 0 new issues; full suite green.

```bash
git add frontend/lib/features/chat/widgets/chat_input.dart \
        frontend/lib/features/chat/chat_notifier.dart \
        frontend/lib/features/chat/chat_page.dart
git commit -m "$(cat <<'EOF'
feat(frontend): wire Smart Reply chips above chat input

ChatInput renders QuickReplyChips above the input row. Tap fills
_controller.text and focuses the field — user reviews + sends
manually (no auto-send).

ChatNotifier._onMessage triggers a 1s-debounced provider refresh
when an incoming message is NOT from self. Self messages skip
(suggesting replies to my own message is nonsense). Multiple
incoming messages within 1s collapse to a single Gemini call via
the debounce.

Confidence: high
Scope-risk: narrow
EOF
)"
```

---

## Final Checklist

- [ ] All 5 tasks committed
- [ ] `./gradlew :ai-summary-service:test` green (was 1; now 1 + 6 new = 7)
- [ ] `cd frontend && flutter test` green (was 214; now 214 + 4 new = 218)
- [ ] `cd frontend && flutter analyze` no new issues vs baseline
- [ ] Manual smoke test: open a chat room, have a friend send a message, verify chips appear within 1-2s, tap a chip → input filled, send works normally
- [ ] No tracked secrets, build artifacts, or `Co-Authored-By: Claude` trailers

## Deploy notes

- `ai-summary-service` rebuild required (new bean + endpoint).
- `frontend` rebuild required (new widget + provider + chat_input changes).
- Other services: no rebuild needed.
- Cloudflare cache purge after frontend deploy.
