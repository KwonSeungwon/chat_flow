package com.chatflow.chat.service.message;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageEditHistoryRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
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
import java.util.Collection;
import java.util.List;
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
    @Mock private MessageMentionRepository messageMentionRepository;
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
            assertThat(published.isEdited()).isFalse();  // delete does NOT set edited
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

        @Test
        @DisplayName("deletes mention rows for the message")
        void deleteMessage_removesMentionRows() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));
            when(chatMessageMapper.toDto(entity)).thenReturn(ChatMessage.builder()
                    .messageId(MESSAGE_ID).chatRoomId(ROOM_ID)
                    .userId(USER_ID).username(USERNAME).content("c").build());

            Result<Void, ChatErrorCode> result = service.deleteMessage(MESSAGE_ID, USER_ID);

            assertThat(result.isSuccess()).isTrue();
            verify(messageMentionRepository).deleteByMessageId(MESSAGE_ID);
        }

        @Test
        @DisplayName("does NOT delete mention rows when delete is forbidden")
        void deleteMessage_forbidden_mentionsUntouched() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

            Result<Void, ChatErrorCode> result = service.deleteMessage(MESSAGE_ID, "other-user");

            assertThat(result.isFailure()).isTrue();
            verifyNoInteractions(messageMentionRepository);
        }
    }

    @Nested
    @DisplayName("editMessage outbox events")
    class EditOutbox {

        @Test
        @DisplayName("produces MESSAGE_EDITED outbox event with new plaintext content, edited=true, and deleted=false")
        void editMessage_publishesOutboxEvent() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.empty());
            when(messageEncryptor.isEnabled()).thenReturn(false);

            // The real mapper would see entity.isEdited()==true (set at line 105
            // before toDto at line 120), so the mock must return edited=true too.
            ChatMessage mappedDto = ChatMessage.builder()
                    .messageId(MESSAGE_ID)
                    .chatRoomId(ROOM_ID)
                    .userId(USER_ID)
                    .username(USERNAME)
                    .content("encrypted-or-new")  // mapper returns entity content; we override
                    .type(BaseMessage.MessageType.CHAT)
                    .timestamp(entity.getTimestamp())
                    .edited(true)
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
            assertThat(published.isEdited()).isTrue();   // Task 0.10: edit DTO carries the flag
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

    @Nested
    @DisplayName("editMessage — mention re-sync")
    class EditMentionResync {

        private static final LocalDateTime MSG_TIMESTAMP = LocalDateTime.of(2026, 7, 4, 12, 0, 0);

        @BeforeEach
        void setUpEditDefaults() {
            // Default stubs shared by mention re-sync tests
            lenient().when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));
            lenient().when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.empty());
            lenient().when(messageEncryptor.isEnabled()).thenReturn(false);
            lenient().when(chatMessageMapper.toDto(entity)).thenReturn(
                    ChatMessage.builder()
                            .messageId(MESSAGE_ID).chatRoomId(ROOM_ID)
                            .userId(USER_ID).username(USERNAME).content("c")
                            .type(BaseMessage.MessageType.CHAT).edited(true)
                            .timestamp(MSG_TIMESTAMP).build());
        }

        private RoomMemberEntity roomMember(String userId, String username) {
            return RoomMemberEntity.builder()
                    .roomId(ROOM_ID).userId(userId).username(username)
                    .role(RoomRole.MEMBER).joinedAt(LocalDateTime.now())
                    .build();
        }

        private MessageMentionEntity existingMention(String userId, String username, boolean read) {
            return MessageMentionEntity.builder()
                    .id((long) userId.hashCode())
                    .messageId(MESSAGE_ID).roomId(ROOM_ID)
                    .mentionedUserId(userId).mentionedUsername(username)
                    .fromUsername(USERNAME).createdAt(MSG_TIMESTAMP).read(read)
                    .build();
        }

        @Test
        @DisplayName("edit that ADDS a mention creates a new mention row (read=false)")
        void editMessage_addsMention_createsRow() {
            String newContent = "hello @bob check this";
            RoomMemberEntity bob = roomMember("bob-id", "bob");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(bob));
            when(messageMentionRepository.findByMessageId(MESSAGE_ID)).thenReturn(List.of());

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<MessageMentionEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(messageMentionRepository).saveAll(captor.capture());
            List<MessageMentionEntity> saved = captor.getValue();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getMentionedUserId()).isEqualTo("bob-id");
            assertThat(saved.get(0).getMentionedUsername()).isEqualTo("bob");
            assertThat(saved.get(0).isRead()).isFalse();
            assertThat(saved.get(0).getFromUsername()).isEqualTo(USERNAME);
            assertThat(saved.get(0).getCreatedAt()).isEqualTo(MSG_TIMESTAMP);

            verify(messageMentionRepository, never()).deleteAll(anyList());
        }

        @Test
        @DisplayName("edit that REMOVES a mention deletes the stale row")
        void editMessage_removesMention_deletesRow() {
            String newContent = "hello everyone"; // no @bob
            MessageMentionEntity bobMention = existingMention("bob-id", "bob", true);
            when(messageMentionRepository.findByMessageId(MESSAGE_ID)).thenReturn(List.of(bobMention));
            // No mentions extracted → no roomMember lookup needed; empty candidates → skip

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<MessageMentionEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(messageMentionRepository).deleteAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getMentionedUserId()).isEqualTo("bob-id");

            verify(messageMentionRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("edit that KEEPS a mention preserves existing row (read state untouched)")
        void editMessage_keepsMention_preservesReadState() {
            String newContent = "updated @bob still here";
            MessageMentionEntity bobMention = existingMention("bob-id", "bob", true);
            RoomMemberEntity bob = roomMember("bob-id", "bob");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(bob));
            when(messageMentionRepository.findByMessageId(MESSAGE_ID)).thenReturn(List.of(bobMention));

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();

            // Neither deleted nor re-inserted — surviving row is untouched
            verify(messageMentionRepository, never()).deleteAll(anyList());
            verify(messageMentionRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("edit mentioning a NON-member does not create a mention row")
        void editMessage_nonMember_noRowAdded() {
            String newContent = "hello @ghost are you there?";
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of()); // ghost is not a member
            when(messageMentionRepository.findByMessageId(MESSAGE_ID)).thenReturn(List.of());

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();
            verify(messageMentionRepository, never()).saveAll(anyList());
            verify(messageMentionRepository, never()).deleteAll(anyList());
        }

        @Test
        @DisplayName("self-mention by the author is excluded")
        void editMessage_selfMention_excluded() {
            String newContent = "I mentioned myself @" + USERNAME;
            // Author's own member entity returned from query
            RoomMemberEntity selfMember = roomMember(USER_ID, USERNAME);
            // But since this is the message author, it is filtered out
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(selfMember));
            when(messageMentionRepository.findByMessageId(MESSAGE_ID)).thenReturn(List.of());

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();
            verify(messageMentionRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("no FCM/notification is fired on edit (structurally guaranteed)")
        void editMessage_noFcmOnEdit() {
            // MessageEditService does NOT inject FcmNotificationService,
            // so no notification can fire. This test documents that guarantee.
            // The send-path in MessageSenderService is the only notification trigger.
            String newContent = "hello @bob mention added";
            RoomMemberEntity bob = roomMember("bob-id", "bob");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(bob));
            when(messageMentionRepository.findByMessageId(MESSAGE_ID)).thenReturn(List.of());

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();
            // FcmNotificationService is not a dependency of MessageEditService.
            // If it were, it would be an @Mock field and we could verify no interactions.
            // Its absence from the constructor IS the structural guarantee.
        }

        @Test
        @DisplayName("non-CHAT message type skips mention re-sync entirely")
        void editMessage_nonChatType_noResync() {
            entity.setType("JOIN"); // not a CHAT message
            String newContent = "some @bob content";

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();
            verify(messageMentionRepository, never()).findByMessageId(anyString());
            verify(roomMemberRepository, never()).findByRoomIdAndUsernameIn(anyString(), anyCollection());
        }

        @Test
        @DisplayName("mixed diff: add carol, remove bob, keep dave")
        void editMessage_mixedDiff_addRemoveKeep() {
            String newContent = "hey @carol and @dave";
            // Existing: bob (read=true), dave (read=true)
            MessageMentionEntity bobMention = existingMention("bob-id", "bob", true);
            MessageMentionEntity daveMention = existingMention("dave-id", "dave", true);
            when(messageMentionRepository.findByMessageId(MESSAGE_ID))
                    .thenReturn(List.of(bobMention, daveMention));

            // Resolved from new content: carol + dave (bob gone)
            RoomMemberEntity carol = roomMember("carol-id", "carol");
            RoomMemberEntity dave = roomMember("dave-id", "dave");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(carol, dave));

            Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, newContent);

            assertThat(result.isSuccess()).isTrue();

            // Bob removed
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<MessageMentionEntity>> removeCaptor = ArgumentCaptor.forClass(List.class);
            verify(messageMentionRepository).deleteAll(removeCaptor.capture());
            assertThat(removeCaptor.getValue()).hasSize(1);
            assertThat(removeCaptor.getValue().get(0).getMentionedUserId()).isEqualTo("bob-id");

            // Carol added
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<MessageMentionEntity>> addCaptor = ArgumentCaptor.forClass(List.class);
            verify(messageMentionRepository).saveAll(addCaptor.capture());
            assertThat(addCaptor.getValue()).hasSize(1);
            assertThat(addCaptor.getValue().get(0).getMentionedUserId()).isEqualTo("carol-id");
            assertThat(addCaptor.getValue().get(0).isRead()).isFalse();

            // Dave: surviving row untouched (still read=true, not re-saved)
        }
    }
}
