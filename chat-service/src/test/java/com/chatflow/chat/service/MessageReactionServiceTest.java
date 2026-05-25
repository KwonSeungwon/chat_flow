package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Unit tests for MessageReactionService covering toggle-on, toggle-off,
 * emoji-key cleanup, and STOMP broadcast payloads.
 */
@ExtendWith(MockitoExtension.class)
class MessageReactionServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private MessageReactionService messageReactionService;

    private static final String ROOM_ID = "room-1";
    private static final String MESSAGE_ID = "msg-1";

    @BeforeEach
    void setUp() {
        messageReactionService = new MessageReactionService(
                chatMessageRepository, new ObjectMapper(), messagingTemplate);
    }

    private ChatMessageEntity sampleMessage(String messageId, String chatRoomId, String reactions) {
        return ChatMessageEntity.builder()
                .messageId(messageId)
                .chatRoomId(chatRoomId)
                .userId("user-1")
                .username("tester")
                .content("hello")
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .type("CHAT")
                .deleted(false)
                .pinned(false)
                .reactions(reactions)
                .build();
    }

    // ── AddReaction (toggle-on) ─────────────────────────────────────

    @Nested
    class AddReaction {

        @Test
        @SuppressWarnings("unchecked")
        void adds_user_to_emoji_list_when_first_reactor() throws Exception {
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, null);
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Boolean, ChatErrorCode> result = messageReactionService.toggleReaction(MESSAGE_ID, "👍", "u1");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.value()).isTrue();

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            String saved = captor.getValue().getReactions();
            assertNotNull(saved);
            // Parse the stored JSON and assert on the map structure
            ObjectMapper om = new ObjectMapper();
            Map<String, java.util.List<String>> parsed = om.readValue(saved,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            assertEquals(1, parsed.size());
            assertTrue(parsed.containsKey("👍"));
            assertEquals(java.util.List.of("u1"), parsed.get("👍"));
        }

        @Test
        void appends_user_when_others_already_reacted_with_same_emoji() {
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, "{\"\\uD83D\\uDC4D\":[\"u1\"]}");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Boolean, ChatErrorCode> result = messageReactionService.toggleReaction(MESSAGE_ID, "👍", "u2");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.value()).isTrue();

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            String saved = captor.getValue().getReactions();
            assertNotNull(saved);
            assertTrue(saved.contains("u1"));
            assertTrue(saved.contains("u2"));
        }
    }

    // ── RemoveReaction (toggle-off) ─────────────────────────────────

    @Nested
    class RemoveReaction {

        @Test
        void removes_user_when_already_in_list_toggling_off() {
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, "{\"\\uD83D\\uDC4D\":[\"u1\",\"u2\"]}");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Boolean, ChatErrorCode> result = messageReactionService.toggleReaction(MESSAGE_ID, "👍", "u1");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.value()).isFalse();

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            String saved = captor.getValue().getReactions();
            assertNotNull(saved);
            assertFalse(saved.contains("u1"));
            assertTrue(saved.contains("u2"));
        }

        @Test
        void removes_emoji_key_when_last_user_unreacts() {
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, "{\"\\uD83D\\uDC4D\":[\"u1\"]}");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            Result<Boolean, ChatErrorCode> result = messageReactionService.toggleReaction(MESSAGE_ID, "👍", "u1");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.value()).isFalse();

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());
            // Production sets reactions to null when map is empty
            assertNull(captor.getValue().getReactions());
        }
    }

    // ── Broadcast ───────────────────────────────────────────────────

    @Nested
    class Broadcast {

        @Test
        @SuppressWarnings("unchecked")
        void broadcasts_REACTION_UPDATED_with_full_map() {
            ChatMessageEntity message = sampleMessage(MESSAGE_ID, ROOM_ID, null);
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

            messageReactionService.toggleReaction(MESSAGE_ID, "👍", "u1");

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("REACTION_UPDATED", payload.get("type"));
            assertEquals(MESSAGE_ID, payload.get("messageId"));
            // Production broadcasts `reactions` as a Map<String, List<String>>, not a JSON string
            assertNotNull(payload.get("reactions"));
            assertTrue(payload.get("reactions") instanceof Map);
            Map<String, ?> reactionsMap = (Map<String, ?>) payload.get("reactions");
            assertTrue(reactionsMap.containsKey("👍"));
        }
    }

    // ── Error paths ────────────────────────────────────────────────

    @Nested
    class ErrorPaths {

        @Test
        void returns_NOT_FOUND_when_message_does_not_exist() {
            when(chatMessageRepository.findById("nonexistent")).thenReturn(Optional.empty());

            Result<Boolean, ChatErrorCode> result =
                    messageReactionService.toggleReaction("nonexistent", "👍", "u1");

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).isEqualTo(ChatErrorCode.NOT_FOUND);
            verify(chatMessageRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
        }
    }

}
