package com.chatflow.search.service;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
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

/**
 * SearchService — deleted-flag handling (Task 0.7).
 * Verifies that a Kafka payload with isDeleted=true triggers deleteById
 * instead of buffering/indexing the document.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService — delete/edit event handling")
class SearchServiceDeleteTest {

    @Mock
    private ChatMessageSearchRepository searchRepository;

    private SearchService searchService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        searchService = new SearchService(searchRepository, objectMapper, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("deleted payload triggers deleteById and skips indexing")
    void deletedMessage_triggersDeleteById() {
        String json = """
                {
                  "messageId": "msg-del-1",
                  "chatRoomId": "room-1",
                  "userId": "user-1",
                  "username": "alice",
                  "content": "삭제된 메시지입니다.",
                  "type": "CHAT",
                  "timestamp": "2026-07-04T12:00:00",
                  "isDeleted": true,
                  "isAiGenerated": false
                }
                """;

        searchService.indexChatMessage(json);

        verify(searchRepository).deleteById("msg-del-1");
        verify(searchRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("normal (non-deleted) payload is buffered for indexing, not deleted")
    void normalMessage_isBuffered() {
        String json = """
                {
                  "messageId": "msg-norm-1",
                  "chatRoomId": "room-1",
                  "userId": "user-1",
                  "username": "alice",
                  "content": "hello world",
                  "type": "CHAT",
                  "timestamp": "2026-07-04T12:00:00",
                  "isDeleted": false,
                  "isAiGenerated": false
                }
                """;

        searchService.indexChatMessage(json);

        verify(searchRepository, never()).deleteById(any());
        // The message is buffered; it won't be flushed until BULK_SIZE (50) is reached
        // or scheduledFlush runs. We verify no deleteById was called.
    }

    @Test
    @DisplayName("edited payload (non-deleted, new content) is buffered for upsert")
    void editedMessage_isBuffered() {
        String json = """
                {
                  "messageId": "msg-edit-1",
                  "chatRoomId": "room-1",
                  "userId": "user-1",
                  "username": "alice",
                  "content": "updated content",
                  "type": "CHAT",
                  "timestamp": "2026-07-04T12:00:00",
                  "isDeleted": false,
                  "isAiGenerated": false
                }
                """;

        searchService.indexChatMessage(json);

        verify(searchRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("deleted payload with JOIN type still triggers deleteById")
    void deletedJoinMessage_stillDeletes() {
        String json = """
                {
                  "messageId": "msg-join-del-1",
                  "chatRoomId": "room-1",
                  "userId": "user-1",
                  "username": "alice",
                  "content": "joined",
                  "type": "JOIN",
                  "timestamp": "2026-07-04T12:00:00",
                  "isDeleted": true,
                  "isAiGenerated": false
                }
                """;

        searchService.indexChatMessage(json);

        verify(searchRepository).deleteById("msg-join-del-1");
        verify(searchRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("payload without isDeleted field defaults to false (backward compat)")
    void missingDeletedField_defaultsFalse() {
        String json = """
                {
                  "messageId": "msg-legacy-1",
                  "chatRoomId": "room-1",
                  "userId": "user-1",
                  "username": "alice",
                  "content": "old message",
                  "type": "CHAT",
                  "timestamp": "2026-07-04T12:00:00",
                  "isAiGenerated": false
                }
                """;

        searchService.indexChatMessage(json);

        verify(searchRepository, never()).deleteById(any());
    }
}
