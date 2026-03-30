package com.chatflow.search.service;

import com.chatflow.common.dto.ChatMessage;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
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

    private final ChatMessageSearchRepository searchRepository;
    private final List<ChatMessageDocument> buffer = new ArrayList<>();
    private final ReentrantLock bufferLock = new ReentrantLock();

    public SearchService(ChatMessageSearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    @KafkaListener(topics = {"chat-messages", "ai-summaries"})
    public void indexChatMessage(ChatMessage message) {
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
                .build();

        bufferLock.lock();
        try {
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
        } catch (Exception e) {
            log.error("Bulk indexing failed for {} messages: {}", batch.size(), e.getMessage());
            // 실패 시 버퍼에 다시 추가 (재시도)
            bufferLock.lock();
            try {
                buffer.addAll(0, batch);
            } finally {
                bufferLock.unlock();
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
