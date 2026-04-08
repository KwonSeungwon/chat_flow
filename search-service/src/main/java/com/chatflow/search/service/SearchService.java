package com.chatflow.search.service;

import com.chatflow.common.dto.ChatMessage;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Kafka 메시지를 수신하여 ES에 벌크 인덱싱.
 * 버퍼에 축적 후 50건 또는 500ms 간격으로 flush.
 */
@Slf4j
@Service
public class SearchService {

    private static final int BULK_SIZE = 50;
    private static final int MAX_BUFFER_SIZE = 5000;
    private static final int MAX_RETRY_COUNT = 3;

    private final ChatMessageSearchRepository searchRepository;
    private final ObjectMapper objectMapper;
    private final List<ChatMessageDocument> buffer = new ArrayList<>();
    private final ReentrantLock bufferLock = new ReentrantLock();
    private int consecutiveFailures = 0;

    public SearchService(ChatMessageSearchRepository searchRepository, ObjectMapper objectMapper, MeterRegistry registry) {
        this.searchRepository = searchRepository;
        this.objectMapper = objectMapper;
        Gauge.builder("chatflow.search.buffer.size", buffer, List::size)
                .description("Search indexing buffer size")
                .register(registry);
    }

    @KafkaListener(topics = {"chat-messages", "ai-summaries"})
    public void indexChatMessage(String messageJson) {
        ChatMessage message;
        try {
            message = objectMapper.readValue(messageJson, ChatMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Kafka 메시지 역직렬화 실패", e);
            return;
        }

        // JOIN/LEAVE/SYSTEM 메시지는 검색 인덱싱 제외
        if (message.getType() != null) {
            var type = message.getType();
            if (type == ChatMessage.MessageType.JOIN || type == ChatMessage.MessageType.LEAVE || type == ChatMessage.MessageType.SYSTEM) {
                return;
            }
        }

        ChatMessageDocument document = ChatMessageDocument.builder()
                .id(message.getMessageId())
                .messageId(message.getMessageId())
                .chatRoomId(message.getChatRoomId())
                .userId(message.getUserId())
                .username(message.getUsername())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .messageType(message.getType().toString())
                .isAiGenerated(message.isAiGenerated())
                .fileName(message.getFileName())
                .fileUrl(message.getFileUrl())
                .fileContentType(message.getFileContentType())
                .build();

        bufferLock.lock();
        try {
            if (buffer.size() >= MAX_BUFFER_SIZE) {
                log.warn("Buffer full ({} messages), dropping oldest batch to prevent OOM", buffer.size());
                buffer.subList(0, BULK_SIZE).clear();
            }
            buffer.add(document);
            if (buffer.size() >= BULK_SIZE) {
                flushBuffer();
            }
        } finally {
            bufferLock.unlock();
        }
    }

    @Scheduled(fixedDelay = 500)
    public void scheduledFlush() {
        bufferLock.lock();
        try {
            if (!buffer.isEmpty()) {
                flushBuffer();
            }
        } finally {
            bufferLock.unlock();
        }
    }

    private void flushBuffer() {
        if (buffer.isEmpty()) return;

        List<ChatMessageDocument> batch = new ArrayList<>(buffer);
        buffer.clear();

        try {
            searchRepository.saveAll(batch);
            log.info("Bulk indexed {} messages", batch.size());
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures <= MAX_RETRY_COUNT) {
                log.error("Bulk indexing failed ({}/{}), re-queuing {} messages: {}",
                        consecutiveFailures, MAX_RETRY_COUNT, batch.size(), e.getMessage());
                buffer.addAll(0, batch);
            } else {
                log.error("Bulk indexing failed {} consecutive times, dropping {} messages to prevent OOM",
                        consecutiveFailures, batch.size());
                consecutiveFailures = 0;
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        bufferLock.lock();
        try {
            if (!buffer.isEmpty()) {
                log.info("Shutdown: flushing {} remaining messages", buffer.size());
                flushBuffer();
            }
        } finally {
            bufferLock.unlock();
        }
    }

    public Page<ChatMessageDocument> searchByContent(String content, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return searchRepository.findByContentContaining(content, pageable);
    }

    public Page<ChatMessageDocument> searchInChatRoom(String chatRoomId, String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return searchRepository.findByChatRoomIdAndContentContaining(chatRoomId, query, pageable);
    }

    public Page<ChatMessageDocument> searchByUsername(String chatRoomId, String username, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return searchRepository.findByChatRoomIdAndUsernameContaining(chatRoomId, username, pageable);
    }

    public Page<ChatMessageDocument> searchByTimeRange(String chatRoomId, LocalDateTime start, LocalDateTime end, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return searchRepository.findByChatRoomIdAndTimestampBetween(chatRoomId, start, end, pageable);
    }
}
