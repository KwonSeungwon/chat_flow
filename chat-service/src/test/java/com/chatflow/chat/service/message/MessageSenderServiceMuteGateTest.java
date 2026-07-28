package com.chatflow.chat.service.message;

import com.chatflow.chat.service.outbox.ChatPersistenceService;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.notification.FcmNotificationService;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for the mute gate added to MessageSenderService.send().
 *
 * <p>The gate covers the types that carry text the user typed — CHAT and FILE
 * (a file ships with a caption). Server-authored JOIN/LEAVE/SYSTEM text is never
 * gated, and must not even hit room_members.
 */
@ExtendWith(MockitoExtension.class)
class MessageSenderServiceMuteGateTest {

    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private FcmNotificationService fcmNotificationService;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private MessageSenderService messageSenderService;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "testuser";

    @BeforeEach
    void setUp() {
        MeterRegistry registry = new SimpleMeterRegistry();
        messageSenderService = new MessageSenderService(
                chatPersistenceService, chatRoomService, fcmNotificationService,
                chatMessageRepository, roomMemberRepository, messagingTemplate, registry);
    }

    private ChatMessage createMessage(ChatMessage.MessageType type) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(USER_ID);
        msg.setUsername(USERNAME);
        msg.setType(type);
        msg.setContent("Hello world");
        return msg;
    }

    private RoomMemberEntity member(LocalDateTime mutedUntil) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_ID)
                .username(USERNAME)
                .role(RoomRole.MEMBER)
                .mutedUntil(mutedUntil)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    // ── Actively muted user ────────────────────────────────────

    @Nested
    class ActivelyMutedUser {

        @Test
        void mutedUser_chatMessageBlocked_errorSentToUser() {
            LocalDateTime mutedUntil = LocalDateTime.now().plusMinutes(30);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(member(mutedUntil)));

            ChatMessage message = createMessage(ChatMessage.MessageType.CHAT);
            messageSenderService.send(message);

            // Verify error sent to user
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSendToUser(
                    eq(USER_ID), eq("/queue/errors"), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("MUTED", payload.get("type"));
            assertEquals(ROOM_ID, payload.get("roomId"));
            assertEquals(mutedUntil.toString(), payload.get("mutedUntil"));

            // Verify persistence was NOT called
            verify(chatPersistenceService, never()).persistMessageAndPublish(
                    any(), anyString(), anyString(), any(), anyList());
        }

        @Test
        void mutedUser_noMessageIdAssigned() {
            LocalDateTime mutedUntil = LocalDateTime.now().plusMinutes(30);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(member(mutedUntil)));

            ChatMessage message = createMessage(ChatMessage.MessageType.CHAT);
            messageSenderService.send(message);

            // messageId should NOT have been assigned (send was blocked before that)
            assertNull(message.getMessageId());
        }
    }

    // ── Mute expired ───────────────────────────────────────────

    @Nested
    class MuteExpired {

        @Test
        void expiredMute_messageSentNormally() {
            LocalDateTime mutedUntil = LocalDateTime.now().minusMinutes(5);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(member(mutedUntil)));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.CHAT);
            messageSenderService.send(message);

            // Verify persistence WAS called
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(), eq(KafkaTopics.CHAT_MESSAGES), eq("MESSAGE_SENT"), any(), anyList());

            // Verify no error sent to user
            verify(messagingTemplate, never()).convertAndSendToUser(
                    anyString(), eq("/queue/errors"), any());
        }
    }

    // ── Non-muted user ─────────────────────────────────────────

    @Nested
    class NonMutedUser {

        @Test
        void nonMutedUser_messageSentNormally() {
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(member(null)));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.CHAT);
            messageSenderService.send(message);

            verify(chatPersistenceService).persistMessageAndPublish(
                    any(), eq(KafkaTopics.CHAT_MESSAGES), eq("MESSAGE_SENT"), any(), anyList());
        }

        @Test
        void userNotInRoom_memberNull_noMuteError() {
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.empty());
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.CHAT);
            messageSenderService.send(message);

            // Message should proceed (let existing handlers decide about non-members)
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(), eq(KafkaTopics.CHAT_MESSAGES), eq("MESSAGE_SENT"), any(), anyList());
            verify(messagingTemplate, never()).convertAndSendToUser(
                    anyString(), eq("/queue/errors"), any());
        }
    }

    // ── Server-authored types skip the mute check ──────────────

    @Nested
    class ServerAuthoredMessageTypes {

        @Test
        void joinMessage_muteCheckSkipped() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.JOIN);
            messageSenderService.send(message);

            // Nobody typed this text, so there is nothing to gate — and no reason
            // to spend a room_members query finding that out.
            verify(roomMemberRepository, never()).findByRoomIdAndUserId(anyString(), anyString());
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(), eq(KafkaTopics.CHAT_MESSAGES), eq("MESSAGE_SENT"), any(), anyList());
        }

        @Test
        void leaveMessage_muteCheckSkipped() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.LEAVE);
            messageSenderService.send(message);

            verify(roomMemberRepository, never()).findByRoomIdAndUserId(anyString(), anyString());
        }

        @Test
        void systemMessage_muteCheckSkipped() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.SYSTEM);
            messageSenderService.send(message);

            verify(roomMemberRepository, never()).findByRoomIdAndUserId(anyString(), anyString());
        }

    }

    // ── FILE is user-authored, so the mute gate covers it ──────

    /**
     * A file arrives with a caption the user typed, and that caption both lands in
     * the room and fires FCM mention pushes at the members it names. Letting a muted
     * user through on FILE would leave them a working way to keep tapping people on
     * the shoulder.
     */
    @Nested
    class FileMessages {

        @Test
        void mutedUser_fileMessageBlocked() {
            LocalDateTime mutedUntil = LocalDateTime.now().plusMinutes(30);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(member(mutedUntil)));

            ChatMessage message = createMessage(ChatMessage.MessageType.FILE);
            messageSenderService.send(message);

            verify(messagingTemplate).convertAndSendToUser(
                    eq(USER_ID), eq("/queue/errors"), any());
            verify(chatPersistenceService, never()).persistMessageAndPublish(
                    any(), anyString(), anyString(), any(), anyList());
            verifyNoInteractions(fcmNotificationService);
            assertNull(message.getMessageId());
        }

        @Test
        void nonMutedUser_fileMessageSentNormally() {
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(member(null)));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            ChatMessage message = createMessage(ChatMessage.MessageType.FILE);
            messageSenderService.send(message);

            // The single-arg overload has to go looking for the mute state itself.
            verify(roomMemberRepository).findByRoomIdAndUserId(ROOM_ID, USER_ID);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(), eq(KafkaTopics.CHAT_MESSAGES), eq("MESSAGE_SENT"), any(), anyList());
            verify(messagingTemplate, never()).convertAndSendToUser(
                    anyString(), eq("/queue/errors"), any());
        }

        /**
         * Real file traffic arrives over STOMP, which hands the member entity in
         * rather than letting send() re-fetch it. That is the overload the gate
         * actually runs on in production, so pin it directly.
         */
        @Test
        void mutedUser_fileMessageBlocked_onThePreResolvedOverload() {
            LocalDateTime mutedUntil = LocalDateTime.now().plusMinutes(30);

            ChatMessage message = createMessage(ChatMessage.MessageType.FILE);
            messageSenderService.send(message, member(mutedUntil));

            verify(messagingTemplate).convertAndSendToUser(
                    eq(USER_ID), eq("/queue/errors"), any());
            verify(chatPersistenceService, never()).persistMessageAndPublish(
                    any(), anyString(), anyString(), any(), anyList());
            // Already resolved by the caller — no second trip to the DB.
            verifyNoInteractions(roomMemberRepository);
            assertNull(message.getMessageId());
        }
    }

    // ── Null userId skips mute check ───────────────────────────

    @Test
    void nullUserId_muteCheckSkipped() {
        when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

        ChatMessage message = createMessage(ChatMessage.MessageType.CHAT);
        message.setUserId(null);
        messageSenderService.send(message);

        verify(roomMemberRepository, never()).findByRoomIdAndUserId(anyString(), anyString());
        verify(chatPersistenceService).persistMessageAndPublish(
                any(), eq(KafkaTopics.CHAT_MESSAGES), eq("MESSAGE_SENT"), any(), anyList());
    }
}
