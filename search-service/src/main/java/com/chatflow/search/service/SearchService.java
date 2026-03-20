package com.chatflow.search.service;

import com.chatflow.common.dto.ChatMessage;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final ChatMessageSearchRepository searchRepository;

    @KafkaListener(topics = {"chat-messages", "ai-summaries"})
    public void indexChatMessage(ChatMessage message) {
        log.info("Indexing message: {} for room: {}", message.getMessageId(), message.getChatRoomId());

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

        searchRepository.save(document);
        log.info("Successfully indexed message: {}", message.getMessageId());
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