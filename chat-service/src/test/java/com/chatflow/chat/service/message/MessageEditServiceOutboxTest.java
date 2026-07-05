package com.chatflow.chat.service.message;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageEditHistoryRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.common.dto.BaseMessage;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MessageEditService — outbox event propagation (Task 0.7).
 * Verifies that delete/edit operations publish to Kafka via the outbox
 * so search-service can update/remove the Elasticsearch document.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageEditService — outbox event propagation")
class MessageEditServiceOutboxTest {

    private static final String MESSAGE_ID = "msg-outbox-1";
    private static final String ROOM_ID = "room-outbox-1";
    private static final String USER_ID = "user-outbox-1";
    private static final String USERNAME = "alice";

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private MessageEncryptor messageEncryptor;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MessageEditHistoryRepository editHistoryRepository;
    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatMessageMapper chatMessageMapper;

    @InjectMocks private MessageEditService service;

    private ChatMessageEntity entity;

    @BeforeEach
    void setUp() {
        entity = new ChatMessageEntity();
        entity.setMessageId(MESSAGE_ID);
        entity.setChatRoomId(ROOM_ID);
        entity.setUserId(USER_ID);
        entity.setUsername(USERNAME);
        entity.setContent("original content");
        entity.setType("CHAT");
        entity.setTimestamp(LocalDateTime.of(2026, 7, 4, 12, 0, 0));
        entity.setFileName("test.pdf");
        entity.setFileUrl("https://example.com/test.pdf");
        entity.setFileContentType("application/pdf");
        entity.setParentMessageId("parent-1");
        entity.setParentMessagePreview("bob: hello");
    }

    @Nested
    @DisplayName("deleteMessage outbox events")
    class DeleteOutbox {

        @Test
        @DisplayName("produces MESSAGE_DELETED outbox event with deleted=true and placeholder content")
        void deleteMessage_publishesOutboxEvent() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

            ChatMessage mappedDto = ChatMessage.builder()
                    .messageId(MESSAGE_ID)
                    .chatRoomId(ROOM_ID)
                    .userId(USER_ID)
                    .username(USERNAME)
                    .content("original content")
                    .type(BaseMessage.MessageType.CHAT)
                    .timestamp(entity.getTimestamp())
                    .fileName("test.pdf")
                    .fileUrl("https://example.com/test.pdf")
                    .fileContentType("application/pdf")
                    .parentMessageId("parent-1")
                    .parentMessagePreview("bob: hello")
                    .build();
            when(chatMessageMapper.toDto(entity)).thenReturn(mappedDto);

            Result<Void, ChatErrorCode> result = service.deleteMessage(MESSAGE_ID, USER_ID);

            assertThat(result.isSuccess()).isTrue();

            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(chatPersistenceService).saveOutboxEvent(
                    captor.capture(),
                    eq(KafkaTopics.CHAT_MESSAGES),
                    eq("MESSAGE_DELETED"));

            ChatMessage published = captor.getValue();
            assertThat(published.isDeleted()).isTrue();
            assertThat(published.getContent()).isEqualTo("삭제된 메시지입니다.");
            assertThat(published.getMessageId()).isEqualTo(MESSAGE_ID);
            assertThat(published.getChatRoomId()).isEqualTo(ROOM_ID);
            assertThat(published.getUsername()).isEqualTo(USERNAME);
            assertThat(published.getTimestamp()).isEqualTo(entity.getTimestamp());
        }

        @Test
        @DisplayName("does NOT publish outbox event when delete is forbidden")
        void deleteMessage_forbidden_noOutboxEvent() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

            Result<Void, ChatErrorCode> result = service.deleteMessage(MESSAGE_ID, "other-user");

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).isEqualTo(ChatErrorCode.FORBIDDEN);
            verifyNoInteractions(chatPersistenceService);
        }
    }

    @Nested
    @DisplayName("editMessage outbox events")
    class EditOutbox {

        @Test
        @DisplayName("produces MESSAGE_EDITED outbox event with new plaintext content and deleted=false")
        void editMessage_publishesOutboxEvent() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.empty());
            when(messageEncryptor.isEnabled()).thenReturn(false);

            ChatMessage mappedDto = ChatMessage.builder()
                    .messageId(MESSAGE_ID)
                    .chatRoomId(ROOM_ID)
                    .userId(USER_ID)
                    .username(USERNAME)
                    .content("encrypted-or-new")  // mapper returns entity content; we override
                    .type(BaseMessage.MessageType.CHAT)
                    .timestamp(entity.getTimestamp())
                    .fileName("test.pdf")
                    .fileUrl("https://example.com/test.pdf")
                    .fileContentType("application/pdf")
                    .parentMessageId("parent-1")
                    .parentMessagePreview("bob: hello")
                    .build();
            when(chatMessageMapper.toDto(entity)).thenReturn(mappedDto);

            String newContent = "edited content";
            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();

            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(chatPersistenceService).saveOutboxEvent(
                    captor.capture(),
                    eq(KafkaTopics.CHAT_MESSAGES),
                    eq("MESSAGE_EDITED"));

            ChatMessage published = captor.getValue();
            assertThat(published.isDeleted()).isFalse();
            assertThat(published.getContent()).isEqualTo(newContent);
            assertThat(published.getMessageId()).isEqualTo(MESSAGE_ID);
            assertThat(published.getChatRoomId()).isEqualTo(ROOM_ID);
        }

        @Test
        @DisplayName("does NOT publish outbox event when edit is forbidden")
        void editMessage_forbidden_noOutboxEvent() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, "other-user", "new");

            assertThat(result.isFailure()).isTrue();
            verifyNoInteractions(chatPersistenceService);
        }

        @Test
        @DisplayName("does NOT publish outbox event when message is already deleted")
        void editMessage_alreadyDeleted_noOutboxEvent() {
            entity.setDeleted(true);
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, "new");

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).isEqualTo(ChatErrorCode.DELETED);
            verifyNoInteractions(chatPersistenceService);
        }
    }
}
