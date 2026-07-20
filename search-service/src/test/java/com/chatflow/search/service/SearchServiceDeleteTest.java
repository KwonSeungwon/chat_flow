package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService — low-level client indexing (bulk) + delete")
class SearchServiceDeleteTest {

    @Mock private ElasticsearchClient elasticsearchClient;
    private SearchService searchService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        searchService = new SearchService(elasticsearchClient, objectMapper, new SimpleMeterRegistry());
    }

    private static String msg(String id, boolean deleted, String type) {
        return """
            {"messageId":"%s","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","type":"%s","timestamp":"2026-07-04T12:00:00",
             "isDeleted":%s,"isAiGenerated":false}""".formatted(id, type, deleted);
    }

    @Test
    @DisplayName("deleted payload calls client.delete and never buffers/bulk-indexes")
    void deleted_callsDelete() throws Exception {
        searchService.indexChatMessage(msg("del-1", true, "CHAT"));
        verify(elasticsearchClient).delete(any(DeleteRequest.class));
        searchService.scheduledFlush();
        verify(elasticsearchClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("normal payload is buffered then bulk-indexed on flush")
    void normal_bulkIndexedOnFlush() throws Exception {
        BulkResponse ok = mock(BulkResponse.class);
        when(ok.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(ok);

        searchService.indexChatMessage(msg("norm-1", false, "CHAT"));
        verify(elasticsearchClient, never()).delete(any(DeleteRequest.class));
        searchService.scheduledFlush();
        verify(elasticsearchClient).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("JOIN/LEAVE/SYSTEM messages are skipped (not buffered)")
    void systemMessage_skipped() throws Exception {
        searchService.indexChatMessage(msg("join-1", false, "JOIN"));
        searchService.scheduledFlush();
        verify(elasticsearchClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("deleted JOIN message still deletes")
    void deletedJoin_stillDeletes() throws Exception {
        searchService.indexChatMessage(msg("join-del-1", true, "JOIN"));
        verify(elasticsearchClient).delete(any(DeleteRequest.class));
    }

    @Test
    @DisplayName("missing isDeleted defaults to false — buffered, not deleted")
    void missingDeleted_defaultsFalse() throws Exception {
        String json = """
            {"messageId":"legacy-1","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","type":"CHAT","timestamp":"2026-07-04T12:00:00","isAiGenerated":false}""";
        searchService.indexChatMessage(json);
        verify(elasticsearchClient, never()).delete(any(DeleteRequest.class));
    }
}
