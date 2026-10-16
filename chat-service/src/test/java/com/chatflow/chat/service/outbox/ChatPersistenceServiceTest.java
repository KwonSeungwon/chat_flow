package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.event.MessagePersistedEvent;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
import com.chatflow.common.dto.BaseMessage;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatPersistenceService covering encryption gating
 * and the published MessagePersistedEvent.
 */
@ExtendWith(MockitoExtension.class)
class ChatPersistenceServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private MessageMentionRepository messageMentionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MessageEncryptor messageEncryptor;
    @Mock private ChatMessageMapper chatMessageMapper;

    private ChatPersistenceService chatPersistenceService;

    private static final String ROOM_ID = "room-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String RAW_CONTENT = "hello world";
    private static final String ENCRYPTED_CONTENT = "enc:aGVsbG8gd29ybGQ=";

    @BeforeEach
    void setUp() {
        chatPersistenceService = new ChatPersistenceService(
                chatMessageRepository, messageMentionRepository, eventPublisher,
                messageEncryptor, chatMessageMapper);
    }

    private ChatMessage sampleMessage() {
        return ChatMessage.builder()
                .messageId(MESSAGE_ID)
                .chatRoomId(ROOM_ID)
                .userId("user-1")
                .username("tester")
                .content(RAW_CONTENT)
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .type(BaseMessage.MessageType.CHAT)
                .build();
    }

    private ChatMessageEntity sampleEntity() {
        return ChatMessageEntity.builder()
                .messageId(MESSAGE_ID)
                .chatRoomId(ROOM_ID)
                .userId("user-1")
                .username("tester")
                .content(RAW_CONTENT)
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .type("CHAT")
                .build();
    }

    // ── Encryption ──────────────────────────────────────────────

    @Nested
    class Encryption {

        @Test
        void encrypts_content_before_save_when_encryptor_enabled() {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(true);
            when(messageEncryptor.encrypt(RAW_CONTENT)).thenReturn(ENCRYPTED_CONTENT);

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(), List.of());

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            assertEquals(ENCRYPTED_CONTENT, captor.getValue().getContent());
            assertNotEquals(RAW_CONTENT, captor.getValue().getContent());
        }

        @Test
        void passes_content_through_when_encryptor_disabled() {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(false);

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(), List.of());

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            assertEquals(RAW_CONTENT, captor.getValue().getContent());
        }
    }

    // ── Published event ─────────────────────────────────────────

    /**
     * 커밋 후 리스너(MessageEventListener)는 본문을 다시 파싱하지 않고 이 이벤트에 실린
     * 이름만 믿는다. 여기가 비면 UNREAD_INCREMENT 페이로드의 mentionedUsernames가 조용히
     * 항상 빈 배열이 되고, 프론트의 "멘션만 알림" 방은 영원히 뱃지를 못 띄운다.
     */
    @Nested
    class PublishedEvent {

        private MessageMentionEntity mentionRow(String userId, String username) {
            return MessageMentionEntity.builder()
                    .messageId(MESSAGE_ID)
                    .roomId(ROOM_ID)
                    .mentionedUserId(userId)
                    .mentionedUsername(username)
                    .fromUsername("tester")
                    .read(false)
                    .build();
        }

        private MessagePersistedEvent capturePublished() {
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(captor.capture());
            return assertInstanceOf(MessagePersistedEvent.class, captor.getValue());
        }

        @Test
        void carries_the_saved_mention_rows_usernames() {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(false);

            // "Phill Park"는 공백이 있어 어떤 문자셋 정규식도 살아남지 못한다 —
            // 리스너가 다시 파싱하지 않는다는 사실을 이 이름이 증명한다.
            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(),
                    List.of(mentionRow("u2", "Phill Park"), mentionRow("u3", "bob")));

            assertEquals(List.of("Phill Park", "bob"),
                    capturePublished().getMentionedUsernames());
        }

        @Test
        void deduplicates_and_drops_null_usernames() {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(false);

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(),
                    List.of(mentionRow("u2", "bob"), mentionRow("u2b", "bob"),
                            mentionRow("u4", null)));

            assertEquals(List.of("bob"), capturePublished().getMentionedUsernames());
        }

        @Test
        void is_empty_when_no_mentions_were_saved() {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(false);

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(), List.of());

            assertTrue(capturePublished().getMentionedUsernames().isEmpty());
            verify(messageMentionRepository, never()).saveAll(any());
        }
    }

    // ── Transactional publishing ────────────────────────────────

    /**
     * MessageEventListener는 fallbackExecution 없는 AFTER_COMMIT 리스너라, TX 밖에서
     * 발행한 이벤트는 조용히 버려진다. @Transactional이 빠지면 단위 테스트는 모두
     * 통과하는데 운영에서 방 브로드캐스트만 사라진다.
     */
    @Nested
    class TransactionalPublishing {

        private static final String WHY = " must be @Transactional: MessageEventListener is "
                + "@TransactionalEventListener(AFTER_COMMIT) without fallbackExecution, so an "
                + "event published outside a transaction is dropped and the room broadcast never happens";

        @Test
        void publishPresence_is_transactional() throws Exception {
            assertNotNull(ChatPersistenceService.class
                            .getMethod("publishPresence", ChatMessage.class)
                            .getAnnotation(Transactional.class),
                    "publishPresence (JOIN/LEAVE)" + WHY);
        }

        @Test
        void persistMessageAndPublish_is_transactional() throws Exception {
            assertNotNull(ChatPersistenceService.class
                            .getMethod("persistMessageAndPublish", ChatMessage.class, List.class)
                            .getAnnotation(Transactional.class),
                    "persistMessageAndPublish" + WHY);
        }
    }
}
