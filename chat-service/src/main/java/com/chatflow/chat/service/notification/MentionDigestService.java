package com.chatflow.chat.service.notification;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
import com.chatflow.common.util.MessageEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MentionDigestService {

    private final MessageMentionRepository mentionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageEncryptor messageEncryptor;

    private static final int MAX_DAYS = 365;
    private static final int PREVIEW_LENGTH = 140;

    private int clamp(int days) {
        return Math.max(1, Math.min(days, MAX_DAYS));
    }

    private String preview(String content) {
        if (content == null) return "";
        return content.length() > PREVIEW_LENGTH
                ? content.substring(0, PREVIEW_LENGTH) + "..."
                : content;
    }

    /**
     * List mention digest items for a user.
     *
     * @param userId   the mentioned user's ID (used for row lookup)
     * @param username unused after the rewrite (kept for API stability)
     * @param days     time window, clamped to [1, 365]
     */
    public List<MentionItemDto> list(String userId, String username, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(clamp(days));
        List<MessageMentionEntity> rows =
                mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, since);
        if (rows.isEmpty()) return List.of();

        Map<String, ChatMessageEntity> messages = chatMessageRepository
                .findAllById(rows.stream().map(MessageMentionEntity::getMessageId).toList())
                .stream()
                .collect(Collectors.toMap(ChatMessageEntity::getMessageId, Function.identity()));

        return rows.stream()
                .map(r -> {
                    ChatMessageEntity msg = messages.get(r.getMessageId());
                    if (msg == null || msg.isDeleted()) return null;
                    String plain = messageEncryptor.isEnabled()
                            ? messageEncryptor.decrypt(msg.getContent())
                            : msg.getContent();
                    return MentionItemDto.of(r, preview(plain));
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * @param username unused after the rewrite (kept for API stability)
     */
    public long unreadCount(String userId, String username, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(clamp(days));
        return mentionRepository.countByMentionedUserIdAndReadFalseAndCreatedAtAfter(userId, since);
    }

    @Transactional
    public void markRead(String userId, String messageId) {
        mentionRepository.markRead(userId, messageId);
    }

    /**
     * @param username unused after the rewrite (kept for API stability)
     */
    @Transactional
    public void markAllRead(String userId, String username, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(clamp(days));
        mentionRepository.markAllRead(userId, since);
    }
}
