package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.util.MessageEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageReadService {

    private final ChatMessageRepository chatMessageRepository;
    private final MessageEncryptor messageEncryptor;

    public Page<ChatMessageEntity> getMessages(String roomId, Pageable pageable) {
        Page<ChatMessageEntity> page = chatMessageRepository.findByChatRoomIdOrderByTimestampDesc(roomId, pageable);
        if (!messageEncryptor.isEnabled()) return page;
        List<ChatMessageEntity> decrypted = page.getContent().stream()
                .map(this::decryptEntity)
                .collect(Collectors.toList());
        return new PageImpl<>(decrypted, pageable, page.getTotalElements());
    }

    public List<ChatMessageEntity> getMessagesByCursor(String roomId, LocalDateTime before, int size) {
        Pageable limit = Pageable.ofSize(size);
        List<ChatMessageEntity> messages = before == null
                ? chatMessageRepository.findLatestByChatRoomId(roomId, limit)
                : chatMessageRepository.findByChatRoomIdBeforeCursor(roomId, before, limit);
        if (!messageEncryptor.isEnabled()) return messages;
        return messages.stream().map(this::decryptEntity).collect(Collectors.toList());
    }

    private ChatMessageEntity decryptEntity(ChatMessageEntity entity) {
        if (entity.getContent() == null) return entity;
        entity.setContent(messageEncryptor.decrypt(entity.getContent()));
        return entity;
    }
}
