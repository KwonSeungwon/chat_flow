package com.chatflow.chat.service.message;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageEditHistoryEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageEditHistoryRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.chatflow.common.util.MessageEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chatflow.chat.entity.MessageMentionEntity;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 메시지 삭제(soft delete) / 편집 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageEditService {

    private static final String DELETED_PLACEHOLDER = "삭제된 메시지입니다.";

    private final ChatMessageRepository chatMessageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final MessageMentionRepository messageMentionRepository;
    private final MessageEncryptor messageEncryptor;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageEditHistoryRepository editHistoryRepository;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatMessageMapper chatMessageMapper;

    @Transactional
    public Result<Void, ChatErrorCode> deleteMessage(String messageId, String requestingUserId) {
        return chatMessageRepository.findById(messageId).<Result<Void, ChatErrorCode>>map(entity -> {
            if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
                return Result.err(ChatErrorCode.FORBIDDEN, "삭제 권한이 없습니다.");
            }
            entity.setDeleted(true);
            entity.setContent(DELETED_PLACEHOLDER);
            chatMessageRepository.save(entity);
            messageMentionRepository.deleteByMessageId(messageId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_DELETED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", entity.getChatRoomId());
            broadcast.put("content", DELETED_PLACEHOLDER);
            broadcast.put("username", entity.getUsername());
            broadcast.put("timestamp", entity.getTimestamp().toString());
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);

            // Publish to Kafka via outbox so search-service removes the ES document
            ChatMessage outboxDto = chatMessageMapper.toDto(entity);
            outboxDto.setDeleted(true);
            outboxDto.setContent(DELETED_PLACEHOLDER);
            chatPersistenceService.saveOutboxEvent(outboxDto, KafkaTopics.CHAT_MESSAGES, "MESSAGE_DELETED");

            log.info("Message deleted: {} by user {}", messageId, requestingUserId);
            return Result.<ChatErrorCode>ok();
        }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
    }

    @Transactional
    public Result<Void, ChatErrorCode> editMessage(String messageId, String requestingUserId, String newContent) {
        return chatMessageRepository.findById(messageId).<Result<Void, ChatErrorCode>>map(entity -> {
            if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
                return Result.<Void, ChatErrorCode>err(ChatErrorCode.FORBIDDEN, "수정 권한이 없습니다.");
            }
            if (entity.isDeleted()) {
                return Result.<Void, ChatErrorCode>err(ChatErrorCode.DELETED, "삭제된 메시지는 수정할 수 없습니다.");
            }
            RoomMemberEntity member = roomMemberRepository
                    .findByRoomIdAndUserId(entity.getChatRoomId(), requestingUserId)
                    .orElse(null);
            if (member != null && member.getMutedUntil() != null
                    && member.getMutedUntil().isAfter(LocalDateTime.now())) {
                log.warn("Muted user {} tried to edit message {} in room {}",
                        requestingUserId, messageId, entity.getChatRoomId());
                return Result.<Void, ChatErrorCode>err(ChatErrorCode.MUTED, "음소거 상태입니다.");
            }
            // Record the OLD content into history BEFORE overwriting. Store
            // the decrypted form so the viewer can render it directly without
            // needing the runtime encryption key (matches the convention used
            // by the live MESSAGE_EDITED broadcast which sends plaintext).
            String previousPlain = messageEncryptor.isEnabled()
                    ? messageEncryptor.decrypt(entity.getContent())
                    : entity.getContent();
            editHistoryRepository.save(MessageEditHistoryEntity.builder()
                    .messageId(messageId)
                    .previousContent(previousPlain)
                    .editedAt(LocalDateTime.now())
                    .editedBy(requestingUserId)
                    .build());

            entity.setContent(messageEncryptor.isEnabled() ? messageEncryptor.encrypt(newContent) : newContent);
            entity.setEdited(true);
            entity.setEditedAt(LocalDateTime.now());
            chatMessageRepository.save(entity);

            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_EDITED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", entity.getChatRoomId());
            broadcast.put("content", newContent);
            broadcast.put("username", entity.getUsername());
            broadcast.put("timestamp", entity.getTimestamp().toString());
            broadcast.put("editedAt", entity.getEditedAt().toString());
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);

            // Publish to Kafka via outbox so search-service upserts the ES document
            ChatMessage outboxDto = chatMessageMapper.toDto(entity);
            outboxDto.setContent(newContent);  // plaintext, NOT the encrypted entity content
            chatPersistenceService.saveOutboxEvent(outboxDto, KafkaTopics.CHAT_MESSAGES, "MESSAGE_EDITED");

            // Re-sync mention rows (digest consistency, no re-notify). Same type rule as
            // the send path — otherwise editing "@bob" out of a FILE caption leaves the
            // row behind and bob keeps seeing a mention that no longer exists.
            if (MentionTargets.carriesUserText(entity.getType())) {
                resyncMentions(entity, newContent);
            }

            log.info("Message edited: {} by user {}", messageId, requestingUserId);
            return Result.<ChatErrorCode>ok();
        }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
    }

    /**
     * Diff-based mention re-sync: add new, delete gone, keep surviving (preserves read state).
     * No FCM/notification — editing is not sending.
     */
    private void resyncMentions(ChatMessageEntity entity, String newContent) {
        // 1. Resolve the edited content against the room's real member list
        List<RoomMemberEntity> mentioned =
                MentionTargets.shouldResolveMentions(
                                entity.getType(), newContent, entity.getFileName())
                        ? MentionTargets.resolve(
                                roomMemberRepository.findByRoomId(entity.getChatRoomId()),
                                newContent, entity.getUsername())
                        : List.of();

        // 2. Index by userId — that is what the existing rows are keyed on
        Map<String, RoomMemberEntity> resolvedByUserId = mentioned.stream()
                .collect(Collectors.toMap(RoomMemberEntity::getUserId, m -> m, (a, b) -> a));
        Set<String> resolvedUserIds = resolvedByUserId.keySet();

        // 3. Load existing mention rows for this message
        List<MessageMentionEntity> existing = messageMentionRepository.findByMessageId(entity.getMessageId());
        Set<String> existingUserIds = existing.stream()
                .map(MessageMentionEntity::getMentionedUserId)
                .collect(Collectors.toSet());

        // 4. Diff — remove stale, add new, keep surviving (preserves read state)
        List<MessageMentionEntity> toRemove = existing.stream()
                .filter(row -> !resolvedUserIds.contains(row.getMentionedUserId()))
                .toList();
        if (!toRemove.isEmpty()) {
            messageMentionRepository.deleteAll(toRemove);
            log.debug("Removed {} stale mention rows for edited message {}",
                    toRemove.size(), entity.getMessageId());
        }

        List<MessageMentionEntity> toAdd = resolvedByUserId.entrySet().stream()
                .filter(e -> !existingUserIds.contains(e.getKey()))
                .map(e -> MessageMentionEntity.builder()
                        .messageId(entity.getMessageId())
                        .roomId(entity.getChatRoomId())
                        .mentionedUserId(e.getKey())
                        .mentionedUsername(e.getValue().getUsername())
                        .fromUsername(entity.getUsername())
                        .createdAt(entity.getTimestamp())
                        .read(false)
                        .build())
                .toList();
        if (!toAdd.isEmpty()) {
            messageMentionRepository.saveAll(toAdd);
            log.debug("Added {} new mention rows for edited message {}",
                    toAdd.size(), entity.getMessageId());
        }
    }
}
