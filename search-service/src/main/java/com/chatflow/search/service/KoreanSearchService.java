package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.exception.SearchException;
import com.chatflow.search.util.SearchConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import co.elastic.clients.json.JsonData;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoreanSearchService {

    private final ElasticsearchClient elasticsearchClient;

    public Page<ChatMessageDocument> searchKoreanContent(String query, String chatRoomId, Pageable pageable) {
        try {
            // Multi-match query with Korean analyzer
            Query multiMatchQuery = MultiMatchQuery.of(m -> m
                    .query(query)
                    .fields("content^3", "content.ngram^0.3", "fileName^2", "fileName.ngram^0.5")
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                    .minimumShouldMatch("75%")
            )._toQuery();

            // Build bool query: must match content + filter by type + optional room
            BoolQuery.Builder boolQueryBuilder = excludeSystemMessages(
                    new BoolQuery.Builder().must(multiMatchQuery));

            if (chatRoomId != null && !chatRoomId.isEmpty()) {
                boolQueryBuilder.filter(f -> f
                        .term(t -> t
                                .field("chatRoomId")
                                .value(chatRoomId)
                        )
                );
            }

            Query finalQuery = boolQueryBuilder.build()._toQuery();

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(SearchConstants.CHAT_MESSAGES_INDEX)
                    .query(finalQuery)
                    .from((int) pageable.getOffset())
                    .size(pageable.getPageSize())
                    .sort(sort -> sort
                            .field(f -> f
                                    .field("timestamp")
                                    .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)
                            )
                    )
                    .highlight(h -> h
                            .fields("content", hf -> hf
                                    .preTags(SearchConstants.HIGHLIGHT_PRE_TAG)
                                    .postTags(SearchConstants.HIGHLIGHT_POST_TAG)
                            )
                    )
            );

            SearchResponse<ChatMessageDocument> response = elasticsearchClient.search(searchRequest, ChatMessageDocument.class);

            List<ChatMessageDocument> documents = new ArrayList<>();
            for (Hit<ChatMessageDocument> hit : response.hits().hits()) {
                ChatMessageDocument doc = hit.source();
                if (doc != null) {
                    // Add highlight information if needed
                    if (hit.highlight() != null && hit.highlight().containsKey("content")) {
                        log.debug("Highlight found for message: {}", doc.getMessageId());
                    }
                    documents.add(doc);
                }
            }

            long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;
            return new PageImpl<>(documents, pageable, totalHits);

        } catch (Exception e) {
            log.error("Error performing Korean search for query: {}", query, e);
            throw new SearchException("검색 중 오류가 발생했습니다.", e);
        }
    }

    public Page<ChatMessageDocument> searchWithNgram(String query, String chatRoomId, Pageable pageable) {
        try {
            // N-gram based search for partial matching
            Query ngramQuery = MultiMatchQuery.of(m -> m
                    .query(query)
                    .fields("content.ngram^2", "fileName.ngram^1.5")
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
            )._toQuery();

            BoolQuery.Builder boolQueryBuilder = excludeSystemMessages(
                    new BoolQuery.Builder().must(ngramQuery));

            if (chatRoomId != null && !chatRoomId.isEmpty()) {
                boolQueryBuilder.filter(f -> f
                        .term(t -> t
                                .field("chatRoomId")
                                .value(chatRoomId)
                        )
                );
            }

            Query finalQuery = boolQueryBuilder.build()._toQuery();

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(SearchConstants.CHAT_MESSAGES_INDEX)
                    .query(finalQuery)
                    .minScore(0.5)
                    .from((int) pageable.getOffset())
                    .size(pageable.getPageSize())
                    .sort(sort -> sort
                            .field(f -> f
                                    .field("timestamp")
                                    .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)
                            )
                    )
                    .highlight(h -> h
                            .fields("content.ngram", hf -> hf
                                    .preTags(SearchConstants.HIGHLIGHT_PRE_TAG)
                                    .postTags(SearchConstants.HIGHLIGHT_POST_TAG)
                            )
                    )
            );

            SearchResponse<ChatMessageDocument> response = elasticsearchClient.search(searchRequest, ChatMessageDocument.class);

            List<ChatMessageDocument> documents = new ArrayList<>();
            for (Hit<ChatMessageDocument> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    documents.add(hit.source());
                }
            }

            long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;
            return new PageImpl<>(documents, pageable, totalHits);

        } catch (Exception e) {
            log.error("Error performing N-gram search for query: {}", query, e);
            throw new SearchException("검색 중 오류가 발생했습니다.", e);
        }
    }

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

            boolBuilder.filter(f -> f.term(t -> t.field("chatRoomId").value(roomId)));

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

            final Query builtQuery = boolBuilder.build()._toQuery();

            SearchRequest request = SearchRequest.of(s -> s
                    .index(SearchConstants.CHAT_MESSAGES_INDEX)
                    .query(builtQuery)
                    .from((int) pageable.getOffset())
                    .size(pageable.getPageSize())
                    .sort(sort -> sort.field(f -> f
                            .field("timestamp")
                            .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))));

            SearchResponse<ChatMessageDocument> response =
                    elasticsearchClient.search(request, ChatMessageDocument.class);

            List<ChatMessageDocument> docs = response.hits().hits().stream()
                    .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            long total = response.hits().total() != null ? response.hits().total().value() : 0;
            return new PageImpl<>(docs, pageable, total);

        } catch (Exception e) {
            log.error("Error in searchWithFilters for roomId: {}", roomId, e);
            throw new SearchException("검색 중 오류가 발생했습니다.", e);
        }
    }

    private static BoolQuery.Builder excludeSystemMessages(BoolQuery.Builder builder) {
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
