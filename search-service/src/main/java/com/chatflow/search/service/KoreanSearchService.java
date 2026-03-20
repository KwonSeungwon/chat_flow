package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.exception.SearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoreanSearchService {

    private final ElasticsearchClient elasticsearchClient;

    public Page<ChatMessageDocument> searchKoreanContent(String query, String chatRoomId, Pageable pageable) {
        try {
            // Multi-match query with Korean analyzer and n-gram support
            Query multiMatchQuery = MultiMatchQuery.of(m -> m
                    .query(query)
                    .fields("content^2", "content.ngram^0.5", "username^1.5")
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                    .fuzziness("AUTO")
            )._toQuery();

            // Build bool query with room filter if provided
            BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder()
                    .should(multiMatchQuery);

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
                    .index("chat_messages")
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
                                    .preTags("<mark>")
                                    .postTags("</mark>")
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
            throw new SearchException("한국어 검색 중 오류가 발생했습니다: " + query, e);
        }
    }

    public Page<ChatMessageDocument> searchWithNgram(String query, String chatRoomId, Pageable pageable) {
        try {
            // N-gram based search for partial matching
            Query ngramQuery = MultiMatchQuery.of(m -> m
                    .query(query)
                    .fields("content.ngram^2")
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
            )._toQuery();

            BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder()
                    .must(ngramQuery);

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
                    .index("chat_messages")
                    .query(finalQuery)
                    .from((int) pageable.getOffset())
                    .size(pageable.getPageSize())
                    .sort(sort -> sort
                            .field(f -> f
                                    .field("timestamp")
                                    .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)
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
            throw new SearchException("N-gram 검색 중 오류가 발생했습니다: " + query, e);
        }
    }
}