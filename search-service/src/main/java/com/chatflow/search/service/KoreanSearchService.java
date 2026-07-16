package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.exception.SearchException;
import com.chatflow.search.util.SearchConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoreanSearchService {

    private final ElasticsearchClient elasticsearchClient;

    public Page<ChatMessageDocument> searchKoreanContent(String query, String chatRoomId, Pageable pageable) {
        BoolQuery.Builder boolBuilder = excludeSystemMessages(
                new BoolQuery.Builder().must(standardMultiMatch(query)));

        if (chatRoomId != null && !chatRoomId.isEmpty()) {
            applyRoomFilter(boolBuilder, chatRoomId);
        }

        return executeAndMap(boolBuilder.build(), pageable, "content", null,
                "Error performing Korean search for query: " + query);
    }

    public Page<ChatMessageDocument> searchWithNgram(String query, String chatRoomId, Pageable pageable) {
        BoolQuery.Builder boolBuilder = excludeSystemMessages(
                new BoolQuery.Builder().must(ngramMultiMatch(query)));

        if (chatRoomId != null && !chatRoomId.isEmpty()) {
            applyRoomFilter(boolBuilder, chatRoomId);
        }

        return executeAndMap(boolBuilder.build(), pageable, "content.ngram", 0.5,
                "Error performing N-gram search for query: " + query);
    }

    public Page<ChatMessageDocument> searchWithFilters(
            String roomId,
            String query,
            String username,
            LocalDateTime startDate,
            LocalDateTime endDate,
            String messageType,
            Pageable pageable) {

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        if (query != null && !query.isBlank()) {
            boolBuilder.must(standardMultiMatch(query));
        } else {
            boolBuilder.must(q -> q.matchAll(m -> m));
        }

        applyRoomFilter(boolBuilder, roomId);

        if (username != null && !username.isBlank()) {
            final String u = username.trim();
            boolBuilder.filter(f -> f.term(t -> t.field("username").value(u)));
        }

        if (startDate != null && endDate != null) {
            final LocalDateTime sd = startDate;
            final LocalDateTime ed = endDate;
            boolBuilder.filter(f -> f.range(r -> r
                    .field("timestamp")
                    .gte(JsonData.of(sd.toString()))
                    .lte(JsonData.of(ed.toString()))));
        }

        if (messageType != null && !messageType.isBlank()) {
            final String mt = messageType.trim();
            boolBuilder.filter(f -> f.term(t -> t.field("messageType").value(mt)));
        } else {
            boolBuilder = excludeSystemMessages(boolBuilder);
        }

        return executeAndMap(boolBuilder.build(), pageable, null, null,
                "Error in searchWithFilters for roomId: " + roomId);
    }

    // ── shared query builders ────────────────────────────────────────────

    /**
     * Standard multi-match: Korean-analyzed content + n-gram fallback + fileName.
     * Used by searchKoreanContent and searchWithFilters.
     */
    static Query standardMultiMatch(String query) {
        return MultiMatchQuery.of(m -> m
                .query(query)
                .fields("content^3", "content.ngram^0.3", "fileName^2", "fileName.ngram^0.5")
                .type(TextQueryType.BestFields)
                .minimumShouldMatch("75%")
        )._toQuery();
    }

    /**
     * N-gram multi-match for partial/substring matching.
     * Used by searchWithNgram.
     */
    static Query ngramMultiMatch(String query) {
        return MultiMatchQuery.of(m -> m
                .query(query)
                .fields("content.ngram^2", "fileName.ngram^1.5")
                .type(TextQueryType.BestFields)
        )._toQuery();
    }

    /**
     * Applies a chatRoomId match filter to the bool query.
     *
     * <p>Uses match() rather than term() — the live chat_messages index in
     * some environments was created before the keyword annotation was set on
     * chatRoomId, leaving the field as the auto-mapped text type. term() does
     * not analyze the query, so it cannot match the analyzed tokens. match()
     * works regardless: against a keyword field it matches the single token,
     * against text it re-tokenizes and matches the produced tokens.</p>
     */
    static void applyRoomFilter(BoolQuery.Builder builder, String chatRoomId) {
        builder.filter(f -> f
                .match(t -> t
                        .field("chatRoomId")
                        .query(chatRoomId)
                )
        );
    }

    // ── shared execution ─────────────────────────────────────────────────

    /**
     * Builds a SearchRequest from the given bool query, executes it, and maps
     * hits to a {@link Page}.
     *
     * <p>Error boundary: the try/catch here wraps only the request assembly,
     * the Elasticsearch call, and hit mapping — i.e. the operations that can
     * realistically fail. Query construction (the multi-match/filter builders
     * in the caller) is pure, non-throwing builder code for the inputs this
     * service receives (the controller guarantees non-null query/roomId), so
     * it deliberately sits outside this boundary. A realistic failure still
     * becomes {@link SearchException} → HTTP 500, unchanged.</p>
     *
     * @param boolQuery       the fully-built bool query
     * @param pageable        pagination (offset + size)
     * @param highlightField  field to highlight, or {@code null} for no highlight
     * @param minScore        minimum score threshold, or {@code null} for none
     * @param errorLogContext message logged (at ERROR) if the search fails
     */
    private Page<ChatMessageDocument> executeAndMap(
            BoolQuery boolQuery, Pageable pageable,
            String highlightField, Double minScore,
            String errorLogContext) {
        try {
            Query finalQuery = boolQuery._toQuery();

            SearchRequest searchRequest = SearchRequest.of(s -> {
                s.index(SearchConstants.CHAT_MESSAGES_INDEX)
                        .query(finalQuery)
                        .from((int) pageable.getOffset())
                        .size(pageable.getPageSize())
                        .sort(sort -> sort
                                .field(f -> f
                                        .field("timestamp")
                                        .order(SortOrder.Desc)
                                )
                        );

                if (minScore != null) {
                    s.minScore(minScore);
                }

                if (highlightField != null) {
                    s.highlight(h -> h
                            .fields(highlightField, hf -> hf
                                    .preTags(SearchConstants.HIGHLIGHT_PRE_TAG)
                                    .postTags(SearchConstants.HIGHLIGHT_POST_TAG)
                            )
                    );
                }

                return s;
            });

            SearchResponse<ChatMessageDocument> response =
                    elasticsearchClient.search(searchRequest, ChatMessageDocument.class);

            List<ChatMessageDocument> docs = response.hits().hits().stream()
                    .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
                    .filter(Objects::nonNull)
                    .toList();

            long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;
            return new PageImpl<>(docs, pageable, totalHits);

        } catch (Exception e) {
            log.error(errorLogContext, e);
            throw new SearchException("검색 중 오류가 발생했습니다.", e);
        }
    }

    static BoolQuery.Builder excludeSystemMessages(BoolQuery.Builder builder) {
        return builder.mustNot(mn -> mn.terms(t -> t
                .field("messageType")
                .terms(tv -> tv.value(
                        SearchConstants.EXCLUDED_MESSAGE_TYPES.stream()
                                .map(FieldValue::of)
                                .toList()
                ))
        ));
    }
}
