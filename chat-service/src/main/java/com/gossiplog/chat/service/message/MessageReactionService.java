package com.gossiplog.chat.service.message;

import com.gossiplog.chat.repository.ChatMessageRepository;
import com.gossiplog.chat.result.ChatErrorCode;
import com.gossiplog.chat.result.Result;
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

    /** Distinct emoji keys per message — bounds the reactions JSON. */
    static final int MAX_DISTINCT_EMOJIS = 50;

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public Result<Boolean, ChatErrorCode> toggleReaction(String roomId, String messageId, String emoji, String userId) {
        return chatMessageRepository.findByIdForUpdate(messageId)
                .filter(entity -> roomId.equals(entity.getChatRoomId()))
                .<Result<Boolean, ChatErrorCode>>map(entity -> {
            Map<String, List<String>> map;
            try {
                map = entity.getReactions() != null
                        ? objectMapper.readValue(entity.getReactions(), new TypeReference<>() {})
                        : new LinkedHashMap<>();
            } catch (Exception e) {
                map = new LinkedHashMap<>();
            }
            if (!map.containsKey(emoji) && map.size() >= MAX_DISTINCT_EMOJIS) {
                return Result.<Boolean, ChatErrorCode>err(ChatErrorCode.INVALID_INPUT,
                        "한 메시지에 리액션은 " + MAX_DISTINCT_EMOJIS + "종류까지 달 수 있습니다.");
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
