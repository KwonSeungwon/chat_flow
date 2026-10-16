package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.SimpleJsonpMapper;
import com.chatflow.search.document.ChatMessageDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Global (multi-room) search must be restricted by a terms filter on chatRoomId. */
@ExtendWith(MockitoExtension.class)
class KoreanSearchServiceRoomScopeTest {

    @Mock private ElasticsearchClient elasticsearchClient;
    @Captor private ArgumentCaptor<SearchRequest> requestCaptor;

    private KoreanSearchService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new KoreanSearchService(elasticsearchClient);
        SearchResponse<ChatMessageDocument> response = SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(HitsMetadata.of(h -> h
                        .total(TotalHits.of(t -> t.value(0).relation(TotalHitsRelation.Eq)))
                        .hits(List.of()))));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(ChatMessageDocument.class)))
                .thenReturn(response);
    }

    @Test
    void koreanContentInRooms_appliesTermsFilterOnChatRoomId() throws IOException {
        service.searchKoreanContentInRooms("테스트", List.of("r1", "r2"), PageRequest.of(0, 10));

        verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
        String json = JsonpUtils.toJsonString(requestCaptor.getValue(), new SimpleJsonpMapper());
        assertThat(json).contains("\"terms\":{\"chatRoomId\":[\"r1\",\"r2\"]}");
    }

    @Test
    void ngramInRooms_appliesTermsFilterOnChatRoomId() throws IOException {
        service.searchWithNgramInRooms("테스트", List.of("r1"), PageRequest.of(0, 10));

        verify(elasticsearchClient).search(requestCaptor.capture(), eq(ChatMessageDocument.class));
        String json = JsonpUtils.toJsonString(requestCaptor.getValue(), new SimpleJsonpMapper());
        assertThat(json).contains("\"terms\":{\"chatRoomId\":[\"r1\"]}");
    }
}
