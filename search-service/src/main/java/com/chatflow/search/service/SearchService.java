package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.chatflow.search.config.SearchKafkaConfig;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.util.SearchConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Kafka 메시지를 수신하여 ES에 벌크 인덱싱 (indexing-only).
 * 읽기(검색)는 {@link KoreanSearchService}에서 담당한다.
 *
 * <p>Durability contract: one Kafka poll batch becomes one bulk request
 * holding index AND delete operations in arrival order. The listener returns
 * (and the container commits offsets) only after the bulk succeeded; any
 * failure is thrown so the shared {@code DefaultErrorHandler} retries and
 * finally dead-letters. Operations are idempotent by document id, so
 * replaying a batch (or its tail) is safe and keeps ordering.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final Set<ChatMessage.MessageType> NON_SEARCHABLE_TYPES = Set.of(
            ChatMessage.MessageType.JOIN, ChatMessage.MessageType.LEAVE, ChatMessage.MessageType.SYSTEM);

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = {KafkaTopics.CHAT_MESSAGES, KafkaTopics.AI_SUMMARIES},
            containerFactory = SearchKafkaConfig.BATCH_LISTENER_CONTAINER_FACTORY)
    public void indexChatMessages(List<String> payloads) {
        List<BulkOperation> operations = new ArrayList<>(payloads.size());
        // operationRecordIndex.get(n) = position in payloads of the record that produced operations.get(n)
        List<Integer> operationRecordIndex = new ArrayList<>(payloads.size());

        for (int i = 0; i < payloads.size(); i++) {
            ChatMessage message;
            try {
                message = objectMapper.readValue(payloads.get(i), ChatMessage.class);
            } catch (JsonProcessingException e) {
                // Persist everything before the bad record, then let the error handler
                // commit up to it and retry/dead-letter from this record onward.
                bulkWrite(operations, operationRecordIndex);
                throw new BatchListenerFailedException("Kafka 메시지 역직렬화 실패", e, i);
            }
            BulkOperation operation = toOperation(message);
            if (operation != null) {
                operations.add(operation);
                operationRecordIndex.add(i);
            }
        }

        bulkWrite(operations, operationRecordIndex);
    }

    /** Maps a message to its index/delete operation, or {@code null} when nothing should be written. */
    private BulkOperation toOperation(ChatMessage message) {
        String id = message.getMessageId();
        if (id == null || id.isBlank()) {
            log.warn("Skipping message without messageId (chatRoomId={}, type={})",
                    message.getChatRoomId(), message.getType());
            return null;
        }

        if (message.isDeleted()) {
            return BulkOperation.of(op -> op.delete(d -> d
                    .index(SearchConstants.CHAT_MESSAGES_INDEX)
                    .id(id)));
        }

        if (message.getType() != null && NON_SEARCHABLE_TYPES.contains(message.getType())) {
            return null;
        }

        ChatMessageDocument document = toDocument(message);
        return BulkOperation.of(op -> op.index(idx -> idx
                .index(SearchConstants.CHAT_MESSAGES_INDEX)
                .id(id)
                .document(document)));
    }

    private static ChatMessageDocument toDocument(ChatMessage message) {
        return ChatMessageDocument.builder()
                .id(message.getMessageId())
                .messageId(message.getMessageId())
                .chatRoomId(message.getChatRoomId())
                .userId(message.getUserId())
                .username(message.getUsername())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .messageType(message.getType() != null ? message.getType().name() : null)
                .isAiGenerated(message.isAiGenerated())
                .fileName(message.getFileName())
                .fileUrl(message.getFileUrl())
                .fileContentType(message.getFileContentType())
                .parentMessageId(message.getParentMessageId())
                .parentMessagePreview(message.getParentMessagePreview())
                .forwardedFrom(message.getForwardedFrom())
                .edited(message.isEdited())
                .build();
    }

    private void bulkWrite(List<BulkOperation> operations, List<Integer> operationRecordIndex) {
        if (operations.isEmpty()) {
            return;
        }

        BulkResponse response;
        try {
            response = elasticsearchClient.bulk(BulkRequest.of(b -> b.operations(operations)));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Bulk indexing request failed (" + operations.size() + " operations)", e);
        }

        if (!response.errors()) {
            log.debug("Bulk wrote {} operations", operations.size());
            return;
        }

        // A delete of a missing document is reported without an error, so any error here is real.
        List<BulkResponseItem> items = response.items();
        for (int n = 0; n < items.size() && n < operationRecordIndex.size(); n++) {
            BulkResponseItem item = items.get(n);
            if (item.error() != null) {
                log.error("Bulk item failed id={} type={} reason={}",
                        item.id(), item.error().type(), item.error().reason());
                throw new BatchListenerFailedException(
                        "Bulk item failed: " + item.error().reason(), operationRecordIndex.get(n));
            }
        }
        throw new IllegalStateException("Bulk response reported errors without a failed item");
    }
}
