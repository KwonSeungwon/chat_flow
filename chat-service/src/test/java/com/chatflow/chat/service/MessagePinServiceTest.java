package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessagePinService covering pin validation, room/message
 * cross-checks, and STOMP broadcast payloads.
 */
@ExtendWith(MockitoExtension.class)
class MessagePinServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RoomCacheEvictor roomCacheEvictor;

    private MessagePinService messagePinService;

    private static final String ROOM_ID = "room-1";
    private static final String MESSAGE_ID = "msg-1";

    @BeforeEach
    void setUp() {
        messagePinService = new MessagePinService(
                chatRoomRepository, chatMessageRepository,
                messagingTemplate, roomCacheEvictor);
    }

    private ChatRoom sampleRoom(String id) {
        return ChatRoom.builder()
                .id(id)
                .name("Room " + id)
                .description("desc")
                .color("#6366f1")
                .roomType(RoomType.GENERAL)
                .participantCount(0)
                .maxParticipants(10)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    private ChatMessageEntity sampleMessage(String messageId, String chatRoomId, boolean deleted) {
        return ChatMessageEntity.builder()
                .messageId(messageId)
                .chatRoomId(chatRoomId)
                .userId("user-1")
                .username("tester")
                .content("hello")
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .type("CHAT")
                .deleted(deleted)
                .pinned(false)
                .build();
    }

    // ── PinMessage ───────────────────────────────────────────────

    @Nested
    class PinMessage {

        @Test
        @SuppressWarnings("unchecked")
        void pins_when_message_belongs_to_same_room_and_is_not_deleted() {
            ChatRoom room = sampleRoom(ROOM_ID);
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, false);

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Void, ChatErrorCode> result = messagePinService.pinMessage(ROOM_ID, MESSAGE_ID);

            assertThat(result.isSuccess()).isTrue();

            // Capture saved room and assert pinnedMessageId
            ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(roomCaptor.capture());
            assertEquals(MESSAGE_ID, roomCaptor.getValue().getPinnedMessageId());

            // Capture broadcast payload and assert type
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID), payloadCaptor.capture());
            assertEquals("MESSAGE_PINNED", payloadCaptor.getValue().get("type"));
        }

        @Test
        void rejects_pin_when_message_belongs_to_different_room() {
            ChatRoom room = sampleRoom(ROOM_ID);
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, "other-room", false);

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Void, ChatErrorCode> result = messagePinService.pinMessage(ROOM_ID, MESSAGE_ID);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).isEqualTo(ChatErrorCode.NOT_FOUND);
            verify(chatRoomRepository, never()).save(any());
        }

        @Test
        void rejects_pin_when_message_is_deleted() {
            ChatRoom room = sampleRoom(ROOM_ID);
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, true);

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Void, ChatErrorCode> result = messagePinService.pinMessage(ROOM_ID, MESSAGE_ID);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).isEqualTo(ChatErrorCode.NOT_FOUND);
            verify(chatRoomRepository, never()).save(any());
        }

        @Test
        void rejects_pin_when_message_not_found() {
            ChatRoom room = sampleRoom(ROOM_ID);

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.empty());

            Result<Void, ChatErrorCode> result = messagePinService.pinMessage(ROOM_ID, MESSAGE_ID);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).isEqualTo(ChatErrorCode.NOT_FOUND);
            verify(chatRoomRepository, never()).save(any());
        }
    }

    // ── UnpinMessage ─────────────────────────────────────────────

    @Nested
    class UnpinMessage {

        @Test
        @SuppressWarnings("unchecked")
        void unpin_clears_pinned_message_id_and_broadcasts() {
            ChatRoom room = sampleRoom(ROOM_ID);
            room.setPinnedMessageId(MESSAGE_ID);

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            // The old pinned message lookup during unpin
            when(chatMessageRepository.findById(MESSAGE_ID))
                    .thenReturn(Optional.of(sampleMessage(MESSAGE_ID, ROOM_ID, false)));

            Result<Void, ChatErrorCode> result = messagePinService.unpinMessage(ROOM_ID);

            assertThat(result.isSuccess()).isTrue();

            // Capture saved room and assert pinnedMessageId cleared
            ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(roomCaptor.capture());
            assertNull(roomCaptor.getValue().getPinnedMessageId());

            // Capture broadcast payload and assert type
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID), payloadCaptor.capture());
            assertEquals("MESSAGE_UNPINNED", payloadCaptor.getValue().get("type"));
        }
    }
}
