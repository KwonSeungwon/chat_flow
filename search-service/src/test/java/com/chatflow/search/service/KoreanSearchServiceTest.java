package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.json.JsonpUtils;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.exception.SearchException;
import com.chatflow.search.util.SearchConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link KoreanSearchService}.
 *
 * <p>Each test captures the emitted {@link SearchRequest} and asserts that the
 * Elasticsearch query DSL (index, from/size, sort, minScore, highlight, bool
 * query structure) is identical to the pre-refactor version.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KoreanSearchService — query-building characterization tests")
class KoreanSearchServiceTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    @Captor
    private ArgumentCaptor<SearchRequest> requestCaptor;

    private KoreanSearchService service;

    @BeforeEach
    void setUp() {
        service = new KoreanSearchService(elasticsearchClient);
    }

    // ── constants (existing tests, preserved) ────────────────────────────

    @Test
    void excludedMessageTypes_shouldContainJoinLeaveSystem() {
        assertThat(SearchConstants.EXCLUDED_MESSAGE_TYPES)
                .containsExactlyInAnyOrder("JOIN", "LEAVE", "SYSTEM");
    }

    @Test
    void chatMessagesIndex_shouldEqualChatMessages() {
        assertThat(SearchConstants.CHAT_MESSAGES_INDEX).isEqualTo("chat_messages");
    }

    @Test
    void highlightTags_shouldBeMarkTags() {
        assertThat(SearchConstants.HIGHLIGHT_PRE_TAG).isEqualTo("<mark>");
        assertThat(SearchConstants.HIGHLIGHT_POST_TAG).isEqualTo("</mark>");
    }

    @Test
    void excludedMessageTypes_shouldBeImmutable() {
        assertThat(SearchConstants.EXCLUDED_MESSAGE_TYPES).isUnmodifiable();
    }

    // ── searchKoreanContent ──────────────────────────────────────────────

    @Nested
    @DisplayName("searchKoreanContent")
    class SearchKoreanContentTests {

        @Test
        @DisplayName("emits correct index, from, size, sort")
        void basicRequestShape() throws IOException {
            stubEmptyResponse();
            Pageable pageable = PageRequest.of(2, 25); // offset=50

            service.searchKoreanContent("테스트", "room-1", pageable);

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            SearchRequest req = requestCaptor.getValue();

            assertThat(req.index()).containsExactly(SearchConstants.CHAT_MESSAGES_INDEX);
            assertThat(req.from()).isEqualTo(50);
            assertThat(req.size()).isEqualTo(25);
            assertThat(req.sort()).hasSize(1);
            assertThat(req.sort().get(0).field().field()).isEqualTo("timestamp");
            assertThat(req.sort().get(0).field().order()).isEqualTo(SortOrder.Desc);
        }

        @Test
        @DisplayName("no minScore is set")
        void noMinScore() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("테스트", "room-1", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            assertThat(requestCaptor.getValue().minScore()).isNull();
        }

        @Test
        @DisplayName("highlights the 'content' field")
        void highlightContent() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("테스트", "room-1", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            SearchRequest req = requestCaptor.getValue();

            assertThat(req.highlight()).isNotNull();
            assertThat(req.highlight().fields()).containsKey("content");
            assertThat(req.highlight().fields().get("content").preTags())
                    .containsExactly(SearchConstants.HIGHLIGHT_PRE_TAG);
            assertThat(req.highlight().fields().get("content").postTags())
                    .containsExactly(SearchConstants.HIGHLIGHT_POST_TAG);
        }

        @Test
        @DisplayName("query contains standard multi-match with correct fields and boosts")
        void standardMultiMatchFields() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("안녕", "room-1", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"multi_match\"");
            assertThat(json).contains("content^3");
            assertThat(json).contains("content.ngram^0.3");
            assertThat(json).contains("fileName^2");
            assertThat(json).contains("fileName.ngram^0.5");
            assertThat(json).contains("best_fields");
            assertThat(json).contains("75%");
        }

        @Test
        @DisplayName("includes chatRoomId match filter when roomId provided")
        void roomFilterPresent() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("테스트", "room-42", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"chatRoomId\"");
            assertThat(json).contains("room-42");
        }

        @Test
        @DisplayName("omits chatRoomId filter when roomId is null")
        void roomFilterAbsentWhenNull() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("테스트", null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            // The filter array should not contain chatRoomId
            assertThat(json).doesNotContain("chatRoomId");
        }

        @Test
        @DisplayName("omits chatRoomId filter when roomId is empty")
        void roomFilterAbsentWhenEmpty() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("테스트", "", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).doesNotContain("chatRoomId");
        }

        @Test
        @DisplayName("excludes system messages (must_not with JOIN/LEAVE/SYSTEM)")
        void excludesSystemMessages() throws IOException {
            stubEmptyResponse();

            service.searchKoreanContent("테스트", null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("must_not");
            assertThat(json).contains("JOIN");
            assertThat(json).contains("LEAVE");
            assertThat(json).contains("SYSTEM");
        }
    }

    // ── searchWithNgram ──────────────────────────────────────────────────

    @Nested
    @DisplayName("searchWithNgram")
    class SearchWithNgramTests {

        @Test
        @DisplayName("emits correct index, from, size, sort")
        void basicRequestShape() throws IOException {
            stubEmptyResponse();
            Pageable pageable = PageRequest.of(1, 20); // offset=20

            service.searchWithNgram("검색", "room-1", pageable);

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            SearchRequest req = requestCaptor.getValue();

            assertThat(req.index()).containsExactly(SearchConstants.CHAT_MESSAGES_INDEX);
            assertThat(req.from()).isEqualTo(20);
            assertThat(req.size()).isEqualTo(20);
            assertThat(req.sort()).hasSize(1);
            assertThat(req.sort().get(0).field().field()).isEqualTo("timestamp");
            assertThat(req.sort().get(0).field().order()).isEqualTo(SortOrder.Desc);
        }

        @Test
        @DisplayName("sets minScore to 0.5")
        void minScoreSet() throws IOException {
            stubEmptyResponse();

            service.searchWithNgram("검색", "room-1", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            assertThat(requestCaptor.getValue().minScore()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("highlights the 'content.ngram' field")
        void highlightNgram() throws IOException {
            stubEmptyResponse();

            service.searchWithNgram("검색", "room-1", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            SearchRequest req = requestCaptor.getValue();

            assertThat(req.highlight()).isNotNull();
            assertThat(req.highlight().fields()).containsKey("content.ngram");
            assertThat(req.highlight().fields().get("content.ngram").preTags())
                    .containsExactly(SearchConstants.HIGHLIGHT_PRE_TAG);
            assertThat(req.highlight().fields().get("content.ngram").postTags())
                    .containsExactly(SearchConstants.HIGHLIGHT_POST_TAG);
        }

        @Test
        @DisplayName("query contains ngram multi-match with correct fields and boosts")
        void ngramMultiMatchFields() throws IOException {
            stubEmptyResponse();

            service.searchWithNgram("검", "room-1", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"multi_match\"");
            assertThat(json).contains("content.ngram^2");
            assertThat(json).contains("fileName.ngram^1.5");
            assertThat(json).contains("best_fields");
            // No minimumShouldMatch for ngram
            assertThat(json).doesNotContain("75%");
        }

        @Test
        @DisplayName("includes chatRoomId match filter when roomId provided")
        void roomFilterPresent() throws IOException {
            stubEmptyResponse();

            service.searchWithNgram("검색", "room-99", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"chatRoomId\"");
            assertThat(json).contains("room-99");
        }

        @Test
        @DisplayName("omits chatRoomId filter when roomId is null")
        void roomFilterAbsentWhenNull() throws IOException {
            stubEmptyResponse();

            service.searchWithNgram("검색", null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).doesNotContain("chatRoomId");
        }
    }

    // ── searchWithFilters ────────────────────────────────────────────────

    @Nested
    @DisplayName("searchWithFilters")
    class SearchWithFiltersTests {

        @Test
        @DisplayName("emits correct index, from, size, sort")
        void basicRequestShape() throws IOException {
            stubEmptyResponse();
            Pageable pageable = PageRequest.of(3, 15); // offset=45

            service.searchWithFilters("room-1", "쿼리", null, null, null, null, pageable);

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            SearchRequest req = requestCaptor.getValue();

            assertThat(req.index()).containsExactly(SearchConstants.CHAT_MESSAGES_INDEX);
            assertThat(req.from()).isEqualTo(45);
            assertThat(req.size()).isEqualTo(15);
            assertThat(req.sort()).hasSize(1);
            assertThat(req.sort().get(0).field().field()).isEqualTo("timestamp");
            assertThat(req.sort().get(0).field().order()).isEqualTo(SortOrder.Desc);
        }

        @Test
        @DisplayName("no minScore is set")
        void noMinScore() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "쿼리", null, null, null, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            assertThat(requestCaptor.getValue().minScore()).isNull();
        }

        @Test
        @DisplayName("no highlight is set")
        void noHighlight() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "쿼리", null, null, null, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            assertThat(requestCaptor.getValue().highlight()).isNull();
        }

        @Test
        @DisplayName("uses standard multi-match when query is non-blank")
        void standardMultiMatchWhenQueryPresent() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "안녕", null, null, null, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("content^3");
            assertThat(json).contains("content.ngram^0.3");
            assertThat(json).contains("fileName^2");
            assertThat(json).contains("fileName.ngram^0.5");
            assertThat(json).contains("75%");
        }

        @Test
        @DisplayName("uses match_all when query is null")
        void matchAllWhenQueryNull() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", null, null, null, null, "FILE", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("match_all");
        }

        @Test
        @DisplayName("uses match_all when query is blank")
        void matchAllWhenQueryBlank() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "  ", null, null, null, "FILE", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("match_all");
        }

        @Test
        @DisplayName("always includes chatRoomId match filter (unconditional)")
        void roomFilterAlwaysPresent() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-77", "test", null, null, null, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"chatRoomId\"");
            assertThat(json).contains("room-77");
        }

        @Test
        @DisplayName("adds username term filter when provided")
        void usernameFilter() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "test", "홍길동", null, null, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"username\"");
            // term filter for username
            assertThat(json).contains("홍길동");
        }

        @Test
        @DisplayName("adds date range filter when both dates provided")
        void dateRangeFilter() throws IOException {
            stubEmptyResponse();
            LocalDateTime start = LocalDateTime.of(2025, 1, 1, 0, 0);
            LocalDateTime end = LocalDateTime.of(2025, 12, 31, 23, 59);

            service.searchWithFilters("room-1", "test", null, start, end, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"range\"");
            assertThat(json).contains("2025-01-01T00:00");
            assertThat(json).contains("2025-12-31T23:59");
        }

        @Test
        @DisplayName("adds messageType term filter and skips excludeSystemMessages when messageType provided")
        void messageTypeFilter() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "test", null, null, null, "FILE", PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("\"messageType\"");
            assertThat(json).contains("FILE");
            // Should NOT have must_not (excludeSystemMessages) when messageType is set
            assertThat(json).doesNotContain("must_not");
        }

        @Test
        @DisplayName("excludes system messages when messageType is null")
        void excludesSystemMessagesWhenNoMessageType() throws IOException {
            stubEmptyResponse();

            service.searchWithFilters("room-1", "test", null, null, null, null, PageRequest.of(0, 10));

            verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
            String json = toJson(requestCaptor.getValue());

            assertThat(json).contains("must_not");
            assertThat(json).contains("JOIN");
            assertThat(json).contains("LEAVE");
            assertThat(json).contains("SYSTEM");
        }
    }

    // ── hit mapping ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("hit mapping")
    class HitMappingTests {

        @Test
        @DisplayName("filters null sources and returns correct page with totalHits")
        void filtersNullSources() throws IOException {
            ChatMessageDocument doc1 = ChatMessageDocument.builder()
                    .messageId("msg-1").chatRoomId("room-1").content("hello").build();
            ChatMessageDocument doc2 = ChatMessageDocument.builder()
                    .messageId("msg-2").chatRoomId("room-1").content("world").build();

            // ES returns 3 hits (2 non-null + 1 null source) with totalHits=100.
            // Using a large total avoids Spring PageImpl's short-circuit: when
            // offset+pageSize > total, PageImpl overrides total with content.size().
            stubResponseWithHits(Arrays.asList(doc1, doc2, null), 100);

            Page<ChatMessageDocument> result = service.searchKoreanContent("hello", "room-1", PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent()).extracting(ChatMessageDocument::getMessageId)
                    .containsExactly("msg-1", "msg-2");
            assertThat(result.getTotalElements()).isEqualTo(100);
        }

        @Test
        @DisplayName("returns empty page when no hits")
        void emptyHits() throws IOException {
            stubEmptyResponse();

            Page<ChatMessageDocument> result = service.searchWithNgram("없는쿼리", "room-1", PageRequest.of(0, 10));

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // ── error handling ───────────────────────────────────────────────────

    @Nested
    @DisplayName("error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("searchKoreanContent wraps IOException in SearchException with Korean message")
        void koreanSearchWrapsException() throws IOException {
            when(elasticsearchClient.search(any(SearchRequest.class), eq(ChatMessageDocument.class)))
                    .thenThrow(new IOException("connection refused"));

            assertThatThrownBy(() -> service.searchKoreanContent("테스트", "room-1", PageRequest.of(0, 10)))
                    .isInstanceOf(SearchException.class)
                    .hasMessage("검색 중 오류가 발생했습니다.")
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("searchWithNgram wraps IOException in SearchException with Korean message")
        void ngramSearchWrapsException() throws IOException {
            when(elasticsearchClient.search(any(SearchRequest.class), eq(ChatMessageDocument.class)))
                    .thenThrow(new IOException("timeout"));

            assertThatThrownBy(() -> service.searchWithNgram("검색", "room-1", PageRequest.of(0, 10)))
                    .isInstanceOf(SearchException.class)
                    .hasMessage("검색 중 오류가 발생했습니다.")
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("searchWithFilters wraps IOException in SearchException with Korean message")
        void filterSearchWrapsException() throws IOException {
            when(elasticsearchClient.search(any(SearchRequest.class), eq(ChatMessageDocument.class)))
                    .thenThrow(new IOException("cluster red"));

            assertThatThrownBy(() -> service.searchWithFilters("room-1", "test", null, null, null, null, PageRequest.of(0, 10)))
                    .isInstanceOf(SearchException.class)
                    .hasMessage("검색 중 오류가 발생했습니다.")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubEmptyResponse() throws IOException {
        TotalHits totalHits = TotalHits.of(t -> t.value(0).relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq));
        HitsMetadata<ChatMessageDocument> hitsMetadata = HitsMetadata.of(h -> h.total(totalHits).hits(List.of()));
        SearchResponse<ChatMessageDocument> response = SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(hitsMetadata)
        );
        when(elasticsearchClient.search(any(SearchRequest.class), eq(ChatMessageDocument.class)))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void stubResponseWithHits(List<ChatMessageDocument> docs, long totalCount) throws IOException {
        List<Hit<ChatMessageDocument>> hits = docs.stream()
                .map(doc -> Hit.of((Hit.Builder<ChatMessageDocument> h) -> {
                        h.index(SearchConstants.CHAT_MESSAGES_INDEX)
                         .id(doc != null ? doc.getMessageId() : "null-id");
                        if (doc != null) {
                            h.source(doc);
                        }
                        return h;
                }))
                .toList();

        TotalHits totalHits = TotalHits.of(t -> t.value(totalCount).relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq));
        HitsMetadata<ChatMessageDocument> hitsMetadata = HitsMetadata.of(h -> h.total(totalHits).hits(hits));
        SearchResponse<ChatMessageDocument> response = SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(hitsMetadata)
        );
        when(elasticsearchClient.search(any(SearchRequest.class), eq(ChatMessageDocument.class)))
                .thenReturn(response);
    }

    /**
     * Serializes a SearchRequest to its JSON representation using the ES
     * client's built-in JSONP serialization. This enables substring-based
     * assertions on the emitted query DSL.
     */
    private static String toJson(SearchRequest request) {
        return JsonpUtils.toJsonString(request, new co.elastic.clients.json.SimpleJsonpMapper());
    }
}
