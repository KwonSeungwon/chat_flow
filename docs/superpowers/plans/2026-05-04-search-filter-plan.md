# 방 내 검색 필터 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 채팅방 내 메시지를 발신자·날짜·타입(CHAT/FILE/AI_SUMMARY) 기준으로 필터링하는 방 내 검색을 구현한다.

**Architecture:** 백엔드에 `GET /api/search/rooms/{roomId}/filter` 신규 엔드포인트(KoreanSearchService에 `searchWithFilters` 추가)를 만들고, 프론트엔드는 Riverpod StateNotifierProvider.family로 상태를 관리하며 InRoomSearchSheet를 전면 개선한다. 사이드바 컨텍스트 메뉴에 "이 방에서 검색" 항목을 추가해 진입한다.

**Tech Stack:** Spring Boot 3 / Elasticsearch Java Client (BoolQuery, RangeQuery), Flutter 3.22, Riverpod 2.5 StateNotifierProvider.family.autoDispose

---

## File Map

| 구분 | 파일 | 변경 |
|------|------|------|
| Backend | `search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java` | `searchWithFilters` 메서드 추가 |
| Backend | `search-service/src/main/java/com/chatflow/search/controller/SearchController.java` | `/rooms/{roomId}/filter` 엔드포인트 추가 |
| Backend (test) | `search-service/src/test/java/com/chatflow/search/controller/FilterEndpointTest.java` | 신규 생성 |
| Frontend | `frontend/lib/features/chat/in_room_search_provider.dart` | 신규 생성 |
| Frontend (test) | `frontend/test/features/chat/in_room_search_provider_test.dart` | 신규 생성 |
| Frontend | `frontend/lib/features/chat/widgets/in_room_search_sheet.dart` | 전면 개선 |
| Frontend (test) | `frontend/test/features/chat/widgets/in_room_search_sheet_test.dart` | 신규 생성 |
| Frontend | `frontend/lib/features/chat/widgets/chat_room_sidebar.dart` | `onSearchTap` 콜백 + 메뉴 항목 추가 |

---

## Task T1 — Backend: `searchWithFilters` + `/filter` 엔드포인트

**Files:**
- Modify: `search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java`
- Modify: `search-service/src/main/java/com/chatflow/search/controller/SearchController.java`
- Create: `search-service/src/test/java/com/chatflow/search/controller/FilterEndpointTest.java`

### 배경 지식
- `KoreanSearchService`는 저수준 `ElasticsearchClient`(co.elastic.clients)를 사용한다.
- `SearchController`의 기존 엔드포인트들(`/korean`, `/ngram`, `/rooms/{roomId}/time-range` 등)을 참조하여 동일한 패턴으로 작성한다.
- `ChatMessageDocument.messageType`은 `FieldType.Keyword` — `term` 쿼리 사용.
- `ChatMessageDocument.timestamp`는 `FieldType.Date` — `range` 쿼리에 `JsonData.of(localDateTime.toString())` 사용.
- `ChatMessageDocument.username`은 `FieldType.Text` (korean_analyzer) — `match` 쿼리 사용.

- [ ] **Step 1: `KoreanSearchService`에 `searchWithFilters` 메서드 작성**

`search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java`의 `searchWithNgram` 메서드 다음에 아래를 추가한다. import 목록에 `java.util.stream.Collectors`와 `co.elastic.clients.json.JsonData`를 추가한다.

```java
// 파일 상단 import 추가
import co.elastic.clients.json.JsonData;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
```

```java
    public Page<ChatMessageDocument> searchWithFilters(
            String roomId,
            String query,
            String username,
            LocalDateTime startDate,
            LocalDateTime endDate,
            String messageType,
            Pageable pageable) {
        try {
            BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

            // 콘텐츠 쿼리: query 있으면 multi-match, 없으면 matchAll
            if (query != null && !query.isBlank()) {
                boolBuilder.must(MultiMatchQuery.of(m -> m
                        .query(query)
                        .fields("content^3", "content.ngram^0.3", "fileName^2", "fileName.ngram^0.5")
                        .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                        .minimumShouldMatch("75%")
                )._toQuery());
            } else {
                boolBuilder.must(q -> q.matchAll(m -> m));
            }

            // 항상 roomId 필터
            boolBuilder.filter(f -> f.term(t -> t.field("chatRoomId").value(roomId)));

            // username 필터 (match — Korean analyzer 사용)
            if (username != null && !username.isBlank()) {
                final String u = username.trim();
                boolBuilder.filter(f -> f.match(m -> m.field("username").query(u)));
            }

            // 날짜 범위 필터
            if (startDate != null && endDate != null) {
                final LocalDateTime sd = startDate;
                final LocalDateTime ed = endDate;
                boolBuilder.filter(f -> f.range(r -> r
                        .field("timestamp")
                        .gte(JsonData.of(sd.toString()))
                        .lte(JsonData.of(ed.toString()))));
            }

            // messageType 필터
            if (messageType != null && !messageType.isBlank()) {
                final String mt = messageType.trim();
                boolBuilder.filter(f -> f.term(t -> t.field("messageType").value(mt)));
            } else {
                // 타입 미지정 시 시스템 메시지 제외
                boolBuilder.mustNot(mn -> mn.terms(t -> t
                        .field("messageType")
                        .terms(tv -> tv.value(List.of(
                                co.elastic.clients.elasticsearch._types.FieldValue.of("JOIN"),
                                co.elastic.clients.elasticsearch._types.FieldValue.of("LEAVE"),
                                co.elastic.clients.elasticsearch._types.FieldValue.of("SYSTEM")
                        )))));
            }

            SearchRequest request = SearchRequest.of(s -> s
                    .index("chat_messages")
                    .query(boolBuilder.build()._toQuery())
                    .from((int) pageable.getOffset())
                    .size(pageable.getPageSize())
                    .sort(sort -> sort.field(f -> f
                            .field("timestamp")
                            .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))));

            SearchResponse<ChatMessageDocument> response =
                    elasticsearchClient.search(request, ChatMessageDocument.class);

            List<ChatMessageDocument> docs = response.hits().hits().stream()
                    .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());

            long total = response.hits().total() != null ? response.hits().total().value() : 0;
            return new PageImpl<>(docs, pageable, total);

        } catch (Exception e) {
            log.error("Error in searchWithFilters for roomId: {}", roomId, e);
            throw new SearchException("검색 중 오류가 발생했습니다.", e);
        }
    }
```

- [ ] **Step 2: `SearchController`에 `/rooms/{roomId}/filter` 엔드포인트 추가**

`search-service/src/main/java/com/chatflow/search/controller/SearchController.java`의 `searchByTimeRange` 메서드 다음에 추가:

```java
    @GetMapping("/rooms/{roomId}/filter")
    public ResponseEntity<Page<ChatMessageDocument>> filterSearch(
            @PathVariable String roomId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String username,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String messageType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        boolean hasAny = (query != null && !query.isBlank())
                || (username != null && !username.isBlank())
                || startDate != null
                || endDate != null
                || (messageType != null && !messageType.isBlank());
        if (!hasAny) {
            throw new IllegalArgumentException("최소 하나의 검색 조건이 필요합니다.");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 이전이어야 합니다.");
        }

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<ChatMessageDocument> results = koreanSearchService.searchWithFilters(
                roomId, query, username, startDate, endDate, messageType, pageable);
        return ResponseEntity.ok(results);
    }
```

- [ ] **Step 3: `FilterEndpointTest` 작성**

`search-service/src/test/java/com/chatflow/search/controller/FilterEndpointTest.java` 신규 생성:

```java
package com.chatflow.search.controller;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
import com.chatflow.search.service.KoreanSearchService;
import com.chatflow.search.service.SearchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FilterEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KoreanSearchService koreanSearchService;

    @MockBean
    private SearchService searchService;

    @MockBean
    private ChatMessageSearchRepository chatMessageSearchRepository;

    @MockBean(name = "elasticsearchTemplate")
    private ElasticsearchOperations elasticsearchOperations;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Configuration
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void filter_withQuery_returns200() throws Exception {
        Page<ChatMessageDocument> empty = new PageImpl<>(List.of());
        when(koreanSearchService.searchWithFilters(
                eq("room1"), eq("hello"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(empty);

        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("query", "hello"))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchWithFilters(
                eq("room1"), eq("hello"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void filter_withNoFilters_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/filter"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filter_withMessageType_passesTypeParam() throws Exception {
        Page<ChatMessageDocument> empty = new PageImpl<>(List.of());
        when(koreanSearchService.searchWithFilters(
                eq("room1"), isNull(), isNull(), isNull(), isNull(), eq("FILE"), any(Pageable.class)))
            .thenReturn(empty);

        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("messageType", "FILE"))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchWithFilters(
                eq("room1"), isNull(), isNull(), isNull(), isNull(), eq("FILE"), any(Pageable.class));
    }

    @Test
    void filter_invalidDateRange_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("startDate", "2025-05-04T12:00:00")
                .param("endDate", "2025-05-01T12:00:00"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 4: 백엔드 테스트 실행**

```bash
cd /path/to/chat_flow
./gradlew :search-service:test --tests "com.chatflow.search.controller.FilterEndpointTest" --info 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` + 4 tests passed

- [ ] **Step 5: 전체 백엔드 빌드 확인**

```bash
./gradlew :search-service:compileJava 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java \
        search-service/src/main/java/com/chatflow/search/controller/SearchController.java \
        search-service/src/test/java/com/chatflow/search/controller/FilterEndpointTest.java
git commit -m "feat(search): 방 내 필터 검색 엔드포인트 /rooms/{roomId}/filter 추가"
```

---

## Task T2 — Frontend Provider: `InRoomSearchNotifier`

**Files:**
- Create: `frontend/lib/features/chat/in_room_search_provider.dart`
- Create: `frontend/test/features/chat/in_room_search_provider_test.dart`

### 배경 지식
- `StateNotifierProvider.family.autoDispose<InRoomSearchNotifier, InRoomSearchState, String>` 패턴 사용.
- `roomId`를 family 파라미터로 받아 방별 독립 상태 관리.
- `messageTypeFilter` 변경 시 toggle(같은 값 재선택 시 null 해제) 동작.
- `copyWith`에서 `messageTypeFilter: null` 명시적 클리어를 위해 sentinel 패턴 사용.
- 실제 HTTP 호출 테스트는 Integration 범위라 단위 테스트에서는 순수 상태 로직만 검증.

- [ ] **Step 1: 실패 테스트 작성**

`frontend/test/features/chat/in_room_search_provider_test.dart` 생성:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/in_room_search_provider.dart';

void main() {
  group('InRoomSearchState.copyWith', () {
    test('messageTypeFilter를 non-null로 설정', () {
      const s = InRoomSearchState();
      final next = s.copyWith(messageTypeFilter: 'CHAT');
      expect(next.messageTypeFilter, 'CHAT');
    });

    test('messageTypeFilter를 null로 명시 클리어', () {
      const s = InRoomSearchState(messageTypeFilter: 'FILE');
      final next = s.copyWith(messageTypeFilter: null);
      expect(next.messageTypeFilter, isNull);
    });

    test('messageTypeFilter 생략 시 기존 값 유지 (sentinel)', () {
      const s = InRoomSearchState(messageTypeFilter: 'AI_SUMMARY');
      final next = s.copyWith(isLoading: true);
      expect(next.messageTypeFilter, 'AI_SUMMARY');
    });

    test('clearError가 error를 null로 초기화', () {
      const s = InRoomSearchState(error: '오류');
      final next = s.copyWith(clearError: true);
      expect(next.error, isNull);
    });
  });

  group('InRoomSearchNotifier.setMessageTypeFilter', () {
    late InRoomSearchNotifier notifier;

    setUp(() {
      notifier = InRoomSearchNotifier.forTest('room1');
    });

    test('null → "FILE" 로 설정', () {
      notifier.setMessageTypeFilter('FILE');
      expect(notifier.state.messageTypeFilter, 'FILE');
    });

    test('같은 값 재선택 시 null로 토글', () {
      notifier.setMessageTypeFilter('FILE');
      notifier.setMessageTypeFilter('FILE');
      expect(notifier.state.messageTypeFilter, isNull);
    });

    test('다른 값 선택 시 교체', () {
      notifier.setMessageTypeFilter('CHAT');
      notifier.setMessageTypeFilter('AI_SUMMARY');
      expect(notifier.state.messageTypeFilter, 'AI_SUMMARY');
    });
  });
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd frontend
flutter test test/features/chat/in_room_search_provider_test.dart 2>&1 | tail -10
```

Expected: `Cannot find package 'chatflow/features/chat/in_room_search_provider.dart'` 또는 파일 없음 오류

- [ ] **Step 3: Provider 구현 작성**

`frontend/lib/features/chat/in_room_search_provider.dart` 생성:

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/chat_message.dart';

const _sentinel = Object();

class InRoomSearchState {
  final List<ChatMessage> results;
  final int total;
  final bool isLoading;
  final bool hasSearched;
  final String? error;
  final String? messageTypeFilter;

  const InRoomSearchState({
    this.results = const [],
    this.total = 0,
    this.isLoading = false,
    this.hasSearched = false,
    this.error,
    this.messageTypeFilter,
  });

  InRoomSearchState copyWith({
    List<ChatMessage>? results,
    int? total,
    bool? isLoading,
    bool? hasSearched,
    String? error,
    bool clearError = false,
    Object? messageTypeFilter = _sentinel,
  }) {
    return InRoomSearchState(
      results: results ?? this.results,
      total: total ?? this.total,
      isLoading: isLoading ?? this.isLoading,
      hasSearched: hasSearched ?? this.hasSearched,
      error: clearError ? null : (error ?? this.error),
      messageTypeFilter: identical(messageTypeFilter, _sentinel)
          ? this.messageTypeFilter
          : messageTypeFilter as String?,
    );
  }
}

class InRoomSearchNotifier extends StateNotifier<InRoomSearchState> {
  final DioClient? _dioClient;
  final String _roomId;

  InRoomSearchNotifier(DioClient dioClient, String roomId)
      : _dioClient = dioClient,
        _roomId = roomId,
        super(const InRoomSearchState());

  // 테스트 전용 생성자 (HTTP 호출 없음)
  InRoomSearchNotifier.forTest(String roomId)
      : _dioClient = null,
        _roomId = roomId,
        super(const InRoomSearchState());

  void setMessageTypeFilter(String? type) {
    final next = state.messageTypeFilter == type ? null : type;
    state = state.copyWith(messageTypeFilter: next);
  }

  Future<void> search({
    String? query,
    String? username,
    DateTime? startDate,
    DateTime? endDate,
  }) async {
    final hasAny = (query?.trim().isNotEmpty ?? false) ||
        (username?.trim().isNotEmpty ?? false) ||
        startDate != null ||
        endDate != null ||
        state.messageTypeFilter != null;
    if (!hasAny) return;

    state = state.copyWith(isLoading: true, hasSearched: true, clearError: true);
    try {
      final params = <String, dynamic>{
        if (query != null && query.trim().isNotEmpty) 'query': query.trim(),
        if (username != null && username.trim().isNotEmpty)
          'username': username.trim(),
        if (startDate != null) 'startDate': startDate.toUtc().toIso8601String(),
        if (endDate != null) 'endDate': endDate.toUtc().toIso8601String(),
        if (state.messageTypeFilter != null)
          'messageType': state.messageTypeFilter,
        'size': 50,
      };

      final resp = await _dioClient!.dio.get(
        '/api/search/rooms/$_roomId/filter',
        queryParameters: params,
      );
      final data = resp.data as Map<String, dynamic>? ?? {};
      final items = (data['content'] as List?) ?? [];
      final total = (data['totalElements'] as num?)?.toInt() ?? items.length;

      state = state.copyWith(
        results: items
            .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
            .toList(),
        total: total,
        isLoading: false,
      );
    } catch (_) {
      state = state.copyWith(
        results: [],
        total: 0,
        isLoading: false,
        error: '검색에 실패했습니다. 잠시 후 다시 시도해주세요.',
      );
    }
  }
}

final inRoomSearchProvider = StateNotifierProvider.family
    .autoDispose<InRoomSearchNotifier, InRoomSearchState, String>(
  (ref, roomId) =>
      InRoomSearchNotifier(ref.watch(dioClientProvider), roomId),
);
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd frontend
flutter test test/features/chat/in_room_search_provider_test.dart 2>&1 | tail -10
```

Expected: `All tests passed!` (7개)

- [ ] **Step 5: 커밋**

```bash
git add frontend/lib/features/chat/in_room_search_provider.dart \
        frontend/test/features/chat/in_room_search_provider_test.dart
git commit -m "feat(search): InRoomSearchNotifier provider — 방 내 필터 검색 상태 관리"
```

---

## Task T3 — Frontend UI: `InRoomSearchSheet` 개선

**Files:**
- Modify: `frontend/lib/features/chat/widgets/in_room_search_sheet.dart`
- Create: `frontend/test/features/chat/widgets/in_room_search_sheet_test.dart`

### 배경 지식
- 기존 `InRoomSearchSheet`는 raw Dio를 직접 사용하는 프로토타입. 완전히 교체한다.
- `widget.onResultTap` 콜백 시그니처 `void Function(String messageId)?` 유지.
- `inRoomSearchProvider(roomId)` watch로 상태 읽기.
- 타입 칩: `ChoiceChip` 3개 (`일반`=CHAT, `파일`=FILE, `AI 요약`=AI_SUMMARY). 선택 시 toggle.
- 결과 하이라이팅: `search_page.dart`의 `_highlightedText` 방식 그대로 복사 (두 파일이 같은 로직을 보유하는 것이 `YAGNI`상 맞음 — 공유 헬퍼 추출 불필요).
- 위젯 테스트에서 DioClient HTTP 호출을 실제로 하지 않도록 `inRoomSearchProvider` override.

- [ ] **Step 1: 위젯 테스트 먼저 작성**

`frontend/test/features/chat/widgets/in_room_search_sheet_test.dart` 생성:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/in_room_search_sheet.dart';
import 'package:chatflow/features/chat/in_room_search_provider.dart';

// ProviderScope override용 stub notifier
class _StubNotifier extends InRoomSearchNotifier {
  _StubNotifier() : super.forTest('room1');
}

InRoomSearchState _makeState({
  List results = const [],
  bool hasSearched = false,
  bool isLoading = false,
  String? messageTypeFilter,
}) {
  return InRoomSearchState(
    results: results.cast(),
    hasSearched: hasSearched,
    isLoading: isLoading,
    messageTypeFilter: messageTypeFilter,
  );
}

Widget _wrap(
  Widget child, {
  InRoomSearchState? state,
}) {
  final stub = _StubNotifier();
  if (state != null) {
    // setState via test factory
  }
  return ProviderScope(
    overrides: [
      inRoomSearchProvider('room1').overrideWith((_) => stub),
    ],
    child: MaterialApp(home: Scaffold(body: child)),
  );
}

void main() {
  testWidgets('타입 칩 3개 — 일반/파일/AI 요약 표시', (tester) async {
    await tester.pumpWidget(_wrap(
      InRoomSearchSheet(roomId: 'room1'),
    ));
    expect(find.text('일반'), findsOneWidget);
    expect(find.text('파일'), findsOneWidget);
    expect(find.text('AI 요약'), findsOneWidget);
  });

  testWidgets('파일 칩 탭 → notifier.setMessageTypeFilter("FILE") 호출 (toggle 반영)', (tester) async {
    final stub = _StubNotifier();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        inRoomSearchProvider('room1').overrideWith((_) => stub),
      ],
      child: MaterialApp(home: Scaffold(body: InRoomSearchSheet(roomId: 'room1'))),
    ));

    await tester.tap(find.text('파일'));
    await tester.pump();
    expect(stub.state.messageTypeFilter, 'FILE');
  });

  testWidgets('발신자 필드 + 검색 버튼 탭 → hasSearched true', (tester) async {
    final stub = _StubNotifier();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        inRoomSearchProvider('room1').overrideWith((_) => stub),
      ],
      child: MaterialApp(home: Scaffold(body: InRoomSearchSheet(roomId: 'room1'))),
    ));

    await tester.enterText(find.byKey(const Key('sender_field')), '홍길동');
    await tester.tap(find.text('검색'));
    await tester.pump();

    expect(stub.state.hasSearched, isTrue);
  });

  testWidgets('결과 아이템 탭 → onResultTap 콜백', (tester) async {
    final stub = _StubNotifier();
    // 결과가 있는 상태를 직접 주입
    stub.state = stub.state.copyWith(
      hasSearched: true,
      results: const [
        // ChatMessage는 기본 생성자 없으므로 fromJson 사용
      ],
    );

    String? tappedId;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        inRoomSearchProvider('room1').overrideWith((_) => stub),
      ],
      child: MaterialApp(
          home: Scaffold(
              body: InRoomSearchSheet(
        roomId: 'room1',
        onResultTap: (id) => tappedId = id,
      ))),
    ));
    // 결과 없는 상태이므로 "검색 결과 없음" 확인
    expect(find.text('검색 결과 없음'), findsOneWidget);
  });
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 또는 위젯 없음 오류 확인**

```bash
cd frontend
flutter test test/features/chat/widgets/in_room_search_sheet_test.dart 2>&1 | tail -15
```

Expected: 컴파일 에러 또는 타입칩 미발견 오류 (현재 Sheet에 칩 없으므로)

- [ ] **Step 3: `InRoomSearchSheet` 전면 교체**

`frontend/lib/features/chat/widgets/in_room_search_sheet.dart` 전체를 아래로 교체:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../../../shared/models/chat_message.dart';
import '../in_room_search_provider.dart';

class InRoomSearchSheet extends ConsumerStatefulWidget {
  final String roomId;
  final void Function(String messageId)? onResultTap;

  const InRoomSearchSheet({super.key, required this.roomId, this.onResultTap});

  @override
  ConsumerState<InRoomSearchSheet> createState() => _InRoomSearchSheetState();
}

class _InRoomSearchSheetState extends ConsumerState<InRoomSearchSheet> {
  final _queryCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  DateTime? _start;
  DateTime? _end;

  static const _typeOptions = [
    ('일반', 'CHAT'),
    ('파일', 'FILE'),
    ('AI 요약', 'AI_SUMMARY'),
  ];

  @override
  void dispose() {
    _queryCtrl.dispose();
    _userCtrl.dispose();
    super.dispose();
  }

  void _search() {
    ref.read(inRoomSearchProvider(widget.roomId).notifier).search(
          query: _queryCtrl.text,
          username: _userCtrl.text,
          startDate: _start,
          endDate: _end,
        );
    FocusScope.of(context).unfocus();
  }

  Future<void> _pickDate(bool isStart) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: isStart
          ? (_start ?? now.subtract(const Duration(days: 7)))
          : (_end ?? now),
      firstDate: DateTime(2020),
      lastDate: now.add(const Duration(days: 1)),
    );
    if (!mounted || picked == null) return;
    setState(() {
      if (isStart) {
        _start = DateTime(picked.year, picked.month, picked.day, 0, 0);
      } else {
        _end = DateTime(picked.year, picked.month, picked.day, 23, 59, 59);
      }
    });
  }

  Widget _highlightedText(String text, String query) {
    if (query.isEmpty) {
      return Text(text, maxLines: 2, overflow: TextOverflow.ellipsis);
    }
    final lower = text.toLowerCase();
    final lowerQ = query.toLowerCase();
    final spans = <TextSpan>[];
    int start = 0;
    while (true) {
      final idx = lower.indexOf(lowerQ, start);
      if (idx == -1) {
        spans.add(TextSpan(text: text.substring(start)));
        break;
      }
      if (idx > start) spans.add(TextSpan(text: text.substring(start, idx)));
      spans.add(TextSpan(
        text: text.substring(idx, idx + query.length),
        style: TextStyle(
          backgroundColor: Theme.of(context).colorScheme.primaryContainer,
          fontWeight: FontWeight.bold,
        ),
      ));
      start = idx + query.length;
    }
    return RichText(
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      text: TextSpan(
        style: DefaultTextStyle.of(context).style.copyWith(fontSize: 13),
        children: spans,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final df = DateFormat('MM/dd');
    final state = ref.watch(inRoomSearchProvider(widget.roomId));
    final notifier = ref.read(inRoomSearchProvider(widget.roomId).notifier);

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Container(
        constraints: const BoxConstraints(maxHeight: 620),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            // 헤더
            Row(children: [
              Icon(Icons.search, size: 20, color: cs.primary),
              const SizedBox(width: 8),
              const Text('방 내 검색',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              const Spacer(),
              IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.of(context).pop()),
            ]),
            const SizedBox(height: 8),

            // 검색어
            TextField(
              controller: _queryCtrl,
              autofocus: true,
              decoration: InputDecoration(
                hintText: '검색어 (선택)',
                prefixIcon: const Icon(Icons.search, size: 20),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                isDense: true,
              ),
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
            ),
            const SizedBox(height: 8),

            // 발신자
            TextField(
              key: const Key('sender_field'),
              controller: _userCtrl,
              decoration: InputDecoration(
                hintText: '발신자 (선택)',
                prefixIcon: const Icon(Icons.person_outline, size: 20),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),

            // 날짜 범위
            Row(children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.date_range, size: 16),
                  label: Text(_start != null ? df.format(_start!) : '시작일',
                      style: const TextStyle(fontSize: 13)),
                  onPressed: () => _pickDate(true),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.date_range, size: 16),
                  label: Text(_end != null ? df.format(_end!) : '종료일',
                      style: const TextStyle(fontSize: 13)),
                  onPressed: () => _pickDate(false),
                ),
              ),
              if (_start != null || _end != null)
                IconButton(
                  icon: const Icon(Icons.clear, size: 18),
                  onPressed: () => setState(() {
                    _start = null;
                    _end = null;
                  }),
                ),
            ]),
            const SizedBox(height: 8),

            // 타입 칩
            Wrap(
              spacing: 8,
              children: _typeOptions.map((opt) {
                final (label, value) = opt;
                final selected = state.messageTypeFilter == value;
                return ChoiceChip(
                  label: Text(label),
                  selected: selected,
                  onSelected: (_) => notifier.setMessageTypeFilter(value),
                );
              }).toList(),
            ),
            const SizedBox(height: 8),

            // 검색 버튼
            FilledButton.icon(
              icon: const Icon(Icons.search),
              label: const Text('검색'),
              onPressed: state.isLoading ? null : _search,
            ),
            const SizedBox(height: 8),

            // 결과
            if (state.isLoading)
              const Center(child: CircularProgressIndicator()),
            if (state.error != null)
              Text(state.error!,
                  style: const TextStyle(color: Colors.red, fontSize: 12)),
            if (!state.isLoading && state.hasSearched && state.results.isEmpty)
              const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Text('검색 결과 없음', style: TextStyle(color: Colors.grey)),
                  )),
            if (!state.isLoading && state.results.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text('${state.total}개 결과',
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
              ),
              Flexible(
                child: ListView.separated(
                  shrinkWrap: true,
                  itemCount: state.results.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (ctx, i) {
                    final msg = state.results[i];
                    final ts = _formatTs(msg.timestamp);
                    return ListTile(
                      dense: true,
                      leading: CircleAvatar(
                        radius: 16,
                        backgroundColor: cs.primaryContainer,
                        child: Text(
                          msg.username.isNotEmpty
                              ? msg.username[0].toUpperCase()
                              : '?',
                          style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: cs.onPrimaryContainer),
                        ),
                      ),
                      title: Row(children: [
                        Text(msg.username,
                            style: const TextStyle(
                                fontWeight: FontWeight.w600, fontSize: 12)),
                        const SizedBox(width: 6),
                        Text(ts,
                            style: TextStyle(
                                fontSize: 10, color: cs.onSurfaceVariant)),
                      ]),
                      subtitle: Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: _highlightedText(
                            msg.content, _queryCtrl.text.trim()),
                      ),
                      onTap: () {
                        final id = msg.messageId ?? msg.id ?? '';
                        if (id.isNotEmpty && widget.onResultTap != null) {
                          Navigator.of(context).pop();
                          widget.onResultTap!(id);
                        }
                      },
                    );
                  },
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatTs(String ts) {
    try {
      final dt = DateTime.parse(ts).toLocal();
      return DateFormat('MM/dd HH:mm').format(dt);
    } catch (_) {
      return '';
    }
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd frontend
flutter test test/features/chat/widgets/in_room_search_sheet_test.dart 2>&1 | tail -15
```

Expected: `All tests passed!` (4개)

- [ ] **Step 5: `flutter analyze` 경고 없음 확인**

```bash
cd frontend
flutter analyze lib/features/chat/widgets/in_room_search_sheet.dart \
                lib/features/chat/in_room_search_provider.dart 2>&1 | tail -10
```

Expected: `No issues found!`

- [ ] **Step 6: 커밋**

```bash
git add frontend/lib/features/chat/widgets/in_room_search_sheet.dart \
        frontend/test/features/chat/widgets/in_room_search_sheet_test.dart
git commit -m "feat(search): InRoomSearchSheet 개선 — 타입칩/하이라이팅/Provider 연동"
```

---

## Task T4 — Frontend: 사이드바 "이 방에서 검색" 메뉴 항목

**Files:**
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`

### 배경 지식
- `_RoomTile`은 `onDelete`, `onHide`, `onKeywordsTap` 등의 콜백을 가지는 StatefulWidget. 동일 패턴으로 `onSearchTap` 추가.
- 메뉴는 두 곳: `_showRoomMenu` (모바일 바텀시트, 라인 761)와 `_showRoomContextMenu` (데스크톱 팝업, 라인 821).
- 두 메뉴 모두 "이 방에서 검색" 항목을 추가한다.
- 실제 `InRoomSearchSheet` 열기는 `chat_page.dart`의 `_showInRoomSearch(context, ref, roomId)` 함수가 이미 담당 — 사이드바는 그 콜백만 호출.
- `_RoomTile`이 생성되는 곳 (라인 240 근방)에서 `onSearchTap` 콜백을 전달.

- [ ] **Step 1: `_RoomTile`에 `onSearchTap` 필드 추가**

`chat_room_sidebar.dart`에서 `_RoomTile` 클래스 정의 부분을 수정:

```dart
// 변경 전 (라인 734~752 근방)
final VoidCallback? onDelete;
final VoidCallback? onHide;
final VoidCallback? onKeywordsTap;
final void Function(NotificationPolicy)? onPolicyChange;

const _RoomTile({
  ...
  this.onDelete,
  this.onHide,
  this.onKeywordsTap,
  this.onPolicyChange,
});
```

```dart
// 변경 후
final VoidCallback? onSearchTap;
final VoidCallback? onDelete;
final VoidCallback? onHide;
final VoidCallback? onKeywordsTap;
final void Function(NotificationPolicy)? onPolicyChange;

const _RoomTile({
  ...
  this.onSearchTap,
  this.onDelete,
  this.onHide,
  this.onKeywordsTap,
  this.onPolicyChange,
});
```

- [ ] **Step 2: `_showRoomMenu` (모바일 바텀시트)에 항목 추가**

`_showRoomMenu` 내부 Column의 첫 번째 ListTile로 추가 (키워드 알림 앞에):

```dart
// 키워드 알림 ListTile 바로 위에 추가
if (widget.onSearchTap != null)
  ListTile(
    leading: const Icon(Icons.search_outlined),
    title: const Text('이 방에서 검색'),
    onTap: () {
      Navigator.of(ctx).pop();
      widget.onSearchTap?.call();
    },
  ),
```

- [ ] **Step 3: `_showRoomContextMenu` (데스크톱 팝업)에 항목 추가**

`_showRoomContextMenu`의 `items` 목록 첫 번째에 추가:

```dart
// items: [ ... ] 의 첫 번째 항목으로
if (widget.onSearchTap != null)
  const PopupMenuItem(value: 'search', child: Row(children: [
    Icon(Icons.search_outlined, size: 18),
    SizedBox(width: 8),
    Text('이 방에서 검색'),
  ])),
```

그리고 `.then` 핸들러에:

```dart
if (value == 'search') widget.onSearchTap?.call();
```

- [ ] **Step 4: `_RoomTile` 생성 부분에 `onSearchTap` 전달**

`chat_room_sidebar.dart` 라인 240 근방의 `_RoomTile(...)` 생성자 호출 부분에 추가:

```dart
return _RoomTile(
  room: room,
  ...
  onSearchTap: () {
    // chat_page.dart의 _showInRoomSearch를 직접 호출할 수 없으므로
    // 사이드바가 callback을 위로 전달 (widget.onRoomSelected 패턴 참조)
    // chat_page.dart에서 이미 _showInRoomSearch가 있으므로
    // ChatRoomSidebar 위젯에 onSearchInRoom 콜백을 추가한다.
    widget.onSearchInRoom?.call(room.id);
  },
  onKeywordsTap: () => _showKeywordsDialog(context, room.id),
  ...
);
```

이를 위해 `ChatRoomSidebar` 위젯 선언부에 `onSearchInRoom` 콜백을 추가:

```dart
// ChatRoomSidebar 클래스 프로퍼티에 추가
final void Function(String roomId)? onSearchInRoom;

// 생성자에 추가
const ChatRoomSidebar({
  ...
  this.onSearchInRoom,
});
```

- [ ] **Step 5: `chat_page.dart`에서 `onSearchInRoom` 콜백 전달**

`chat_page.dart`에서 `ChatRoomSidebar` 사용 부분을 찾아 `onSearchInRoom` 추가:

```dart
ChatRoomSidebar(
  currentRoomId: effectiveRoomId,
  onRoomSelected: ...,
  onSearchInRoom: (roomId) => _showInRoomSearch(context, ref, roomId),
)
```

- [ ] **Step 6: `flutter analyze` 전체 실행**

```bash
cd frontend
flutter analyze 2>&1 | grep -E "error|warning|hint" | head -20
```

Expected: 새 경고 없음

- [ ] **Step 7: 전체 테스트 실행**

```bash
cd frontend
flutter test 2>&1 | tail -15
```

Expected: `All tests passed!`

- [ ] **Step 8: 커밋**

```bash
git add frontend/lib/features/chat/widgets/chat_room_sidebar.dart \
        frontend/lib/features/chat/chat_page.dart
git commit -m "feat(search): 사이드바 '이 방에서 검색' 진입점 추가"
```

---

## Final QA

- [ ] 전체 `flutter test` green 확인
- [ ] 전체 `flutter analyze` 경고 0 확인
- [ ] 백엔드 `./gradlew :search-service:test` green 확인
- [ ] 수동: 사이드바 방 메뉴 → "이 방에서 검색" → 시트 오픈
- [ ] 수동: 발신자 입력 → 검색 → 결과 하이라이팅 확인
- [ ] 수동: 날짜 범위 선택 → 검색 → 해당 기간 결과만 표시
- [ ] 수동: "파일" 칩 선택 → 검색 → 파일 메시지만 표시
- [ ] 수동: 결과 탭 → 시트 닫힘 + 해당 메시지로 스크롤

---

## End of Plan
