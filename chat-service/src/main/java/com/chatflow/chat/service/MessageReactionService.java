package com.chatflow.chat.service;

import com.chatflow.chat.repository.ChatMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 메시지 이모지 리액션 토글 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReactionService {

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public boolean toggleReaction(String messageId, String emoji, String userId) {
        return chatMessageRepository.findById(messageId).map(entity -> {
            Map<String, List<String>> map;
            try {
                map = entity.getReactions() != null
                        ? objectMapper.readValue(entity.getReactions(), new TypeReference<>() {})
                        : new LinkedHashMap<>();
            } catch (Exception e) {
                map = new LinkedHashMap<>();
            }
            List<String> users = map.computeIfAbsent(emoji, k -> new java.util.ArrayList<>());
            if (users.contains(userId)) {
                users.remove(userId);
                if (users.isEmpty()) map.remove(emoji);
            } else {
                users.add(userId);
            }
            try {
                entity.setReactions(map.isEmpty() ? null : objectMapper.writeValueAsString(map));
            } catch (Exception e) {
                return false;
            }
            chatMessageRepository.save(entity);
            // Broadcast reaction update
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "REACTION_UPDATED");
            broadcast.put("messageId", messageId);
            broadcast.put("reactions", map);
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            return true;
        }).orElse(false);
    }
}
