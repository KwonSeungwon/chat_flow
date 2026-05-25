package com.chatflow.chat.service;

import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
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
    public Result<Boolean, ChatErrorCode> toggleReaction(String messageId, String emoji, String userId) {
        return chatMessageRepository.findById(messageId).<Result<Boolean, ChatErrorCode>>map(entity -> {
            Map<String, List<String>> map;
            try {
                map = entity.getReactions() != null
                        ? objectMapper.readValue(entity.getReactions(), new TypeReference<>() {})
                        : new LinkedHashMap<>();
            } catch (Exception e) {
                map = new LinkedHashMap<>();
            }
            List<String> users = map.computeIfAbsent(emoji, k -> new java.util.ArrayList<>());
            boolean added;
            if (users.contains(userId)) {
                users.remove(userId);
                if (users.isEmpty()) map.remove(emoji);
                added = false;
            } else {
                users.add(userId);
                added = true;
            }
            try {
                entity.setReactions(map.isEmpty() ? null : objectMapper.writeValueAsString(map));
            } catch (Exception e) {
                return Result.<Boolean, ChatErrorCode>err(ChatErrorCode.INTERNAL_ERROR, "리액션 직렬화 실패");
            }
            chatMessageRepository.save(entity);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "REACTION_UPDATED");
            broadcast.put("messageId", messageId);
            broadcast.put("reactions", map);
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            return Result.<Boolean, ChatErrorCode>ok(added);
        }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
    }
}
