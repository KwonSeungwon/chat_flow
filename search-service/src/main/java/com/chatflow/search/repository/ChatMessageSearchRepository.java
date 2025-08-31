package com.chatflow.search.repository;

import com.chatflow.search.document.ChatMessageDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatMessageSearchRepository extends ElasticsearchRepository<ChatMessageDocument, String> {
    
    Page<ChatMessageDocument> findByChatRoomIdAndContentContaining(
            String chatRoomId, String content, Pageable pageable);
    
    Page<ChatMessageDocument> findByChatRoomIdAndUsernameContaining(
            String chatRoomId, String username, Pageable pageable);
    
    Page<ChatMessageDocument> findByChatRoomIdAndTimestampBetween(
            String chatRoomId, LocalDateTime start, LocalDateTime end, Pageable pageable);
    
    List<ChatMessageDocument> findByChatRoomIdOrderByTimestampDesc(String chatRoomId);
    
    Page<ChatMessageDocument> findByContentContaining(String content, Pageable pageable);
}