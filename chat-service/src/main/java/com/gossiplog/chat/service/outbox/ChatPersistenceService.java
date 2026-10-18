package com.gossiplog.chat.service.outbox;

import com.gossiplog.chat.entity.ChatMessageEntity;
import com.gossiplog.chat.entity.MessageMentionEntity;
import com.gossiplog.chat.event.MessagePersistedEvent;
import com.gossiplog.chat.mapper.ChatMessageMapper;
import com.gossiplog.chat.repository.ChatMessageRepository;
import com.gossiplog.chat.repository.MessageMentionRepository;
import com.gossiplog.common.dto.ChatMessage;
import com.gossiplog.common.util.MessageEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private final ChatMessageRepository chatMessageRepository;
    private final MessageMentionRepository messageMentionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageEncryptor messageEncryptor;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 메시지 + 멘션 행을 단일 TX로 영속화.
     * TX 커밋 후 MessagePersistedEvent → WebSocket 브로드캐스트.
     * 멘션 행은 발신 시점 room_members 기준으로 호출자가 미리 구성해 전달한다.
     *
     * @param mentions 멘션 엔티티 목록 (empty OK, null-safe)
     */
    @Transactional
    public void persistMessageAndPublish(ChatMessage message, List<MessageMentionEntity> mentions) {
        ChatMessageEntity entity = chatMessageMapper.toEntity(message);
        if (messageEncryptor.isEnabled()) {
            entity.setContent(messageEncryptor.encrypt(message.getContent()));
        }
        chatMessageRepository.save(entity);

        if (mentions != null && !mentions.isEmpty()) {
            messageMentionRepository.saveAll(mentions);
        }

        // 리스너가 본문을 다시 파싱하지 않도록, 방금 확정한 멘션 대상을 그대로 실어 보낸다.
        eventPublisher.publishEvent(new MessagePersistedEvent(message, mentionedUsernames(mentions)));
    }

    private static List<String> mentionedUsernames(List<MessageMentionEntity> mentions) {
        if (mentions == null || mentions.isEmpty()) return List.of();
        return mentions.stream()
                .map(MessageMentionEntity::getMentionedUsername)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * JOIN/LEAVE: DB 행 없이 이벤트만 발행한다.
     * 리스너가 AFTER_COMMIT이라 TX 안에서 발행해야 브로드캐스트가 나간다.
     */
    @Transactional
    public void publishPresence(ChatMessage message) {
        eventPublisher.publishEvent(new MessagePersistedEvent(message));
    }
}
