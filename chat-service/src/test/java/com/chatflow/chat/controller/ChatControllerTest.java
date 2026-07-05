package com.chatflow.chat.controller;

import com.chatflow.chat.service.ChatService;
import com.chatflow.chat.service.read.ReadReceiptService;
import com.chatflow.chat.service.room.RoomMembershipChecker;
import com.chatflow.common.dto.ChatMessage;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock private ChatService chatService;
    @Mock private ReadReceiptService readReceiptService;
    @Mock private Validator validator;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RoomMembershipChecker membershipChecker;

    @InjectMocks
    private ChatController chatController;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "testuser";

    private SimpMessageHeaderAccessor headerAccessor;

    @BeforeEach
    void setUp() {
        headerAccessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("userId", USER_ID);
        sessionAttrs.put("username", USERNAME);
        headerAccessor.setSessionId("session-1");
        headerAccessor.setSessionAttributes(sessionAttrs);
    }

    // ── sendMessage membership gate ────────────────────────────────

    @Test
    @DisplayName("sendMessage rejects non-member via isMember gate")
    void sendMessage_rejects_non_member_via_isMember_gate() {
        ChatMessage message = buildChatMessage();

        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(false);

        chatController.sendMessage(message, headerAccessor);

        // chatService.processMessage should never be called
        verify(chatService, never()).processMessage(any(ChatMessage.class));
        // Non-member error sent to user queue
        verify(messagingTemplate).convertAndSendToUser(
                eq(USER_ID), eq("/queue/errors"), any(Map.class));
    }

    @Test
    @DisplayName("sendMessage passes through when member")
    void sendMessage_passes_through_when_member() {
        ChatMessage message = buildChatMessage();

        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
        // Validator returns no violations
        when(validator.validate(any(ChatMessage.class))).thenReturn(Set.of());

        chatController.sendMessage(message, headerAccessor);

        verify(chatService).processMessage(message);
    }

    @Test
    @DisplayName("sendMessage passes through via legacy createdBy fallback")
    void sendMessage_passes_through_via_legacy_createdBy_fallback() {
        ChatMessage message = buildChatMessage();

        // RoomMembershipChecker handles the legacy createdBy fallback internally
        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
        when(validator.validate(any(ChatMessage.class))).thenReturn(Set.of());

        chatController.sendMessage(message, headerAccessor);

        verify(chatService).processMessage(message);
    }

    // ── addUser ────────────────────────────────────────────────────

    @Test
    @DisplayName("addUser calls chatService.addUser with sessionId")
    void addUser_calls_chatService_addUser_with_sessionId() {
        ChatMessage message = buildChatMessage();

        chatController.addUser(message, headerAccessor);

        verify(chatService).addUser(message, "session-1");
    }

    @Test
    @DisplayName("addUser overwrites message userId/username from session attributes")
    void addUser_overwrites_userId_username_from_session() {
        ChatMessage message = new ChatMessage();
        message.setChatRoomId(ROOM_ID);
        message.setUserId("spoofed-id");
        message.setUsername("spoofed-name");
        message.setType(ChatMessage.MessageType.JOIN);

        chatController.addUser(message, headerAccessor);

        // Session attributes should overwrite spoofed values
        verify(chatService).addUser(message, "session-1");
        // The message object should have been mutated to use session values
        org.assertj.core.api.Assertions.assertThat(message.getUserId()).isEqualTo(USER_ID);
        org.assertj.core.api.Assertions.assertThat(message.getUsername()).isEqualTo(USERNAME);
    }

    // ── typing membership gate ─────────────────────────────────────

    @Test
    @DisplayName("typing publishes TYPING event only when member")
    void typing_publishes_TYPING_event_only_when_member() {
        Map<String, String> payload = Map.of(
                "chatRoomId", ROOM_ID,
                "username", USERNAME);

        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);

        chatController.typing(payload, headerAccessor);

        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/typing"),
                any(Map.class));
    }

    @Test
    @DisplayName("typing skips broadcast when non-member")
    void typing_skips_broadcast_when_non_member() {
        Map<String, String> payload = Map.of(
                "chatRoomId", ROOM_ID,
                "username", USERNAME);

        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(false);

        chatController.typing(payload, headerAccessor);

        // Typing broadcast should NOT happen
        verify(messagingTemplate, never()).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/typing"),
                any(Map.class));
        // Non-member error sent to user queue
        verify(messagingTemplate).convertAndSendToUser(
                eq(USER_ID), eq("/queue/errors"), any(Map.class));
    }

    @Test
    @DisplayName("typing returns early when chatRoomId is null")
    void typing_returns_early_when_chatRoomId_null() {
        Map<String, String> payload = new HashMap<>();
        payload.put("chatRoomId", null);

        chatController.typing(payload, headerAccessor);

        verifyNoInteractions(membershipChecker);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    // ── markRead membership gate ───────────────────────────────────

    @Test
    @DisplayName("markRead calls readReceiptService only when member")
    void markRead_calls_read_receipt_only_when_member() {
        Map<String, String> payload = Map.of(
                "roomId", ROOM_ID,
                "lastReadMessageId", "msg-42");

        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);

        chatController.markRead(payload, headerAccessor);

        verify(readReceiptService).markRead(ROOM_ID, USER_ID, USERNAME, "msg-42");
    }

    @Test
    @DisplayName("markRead rejects non-member")
    void markRead_rejects_non_member() {
        Map<String, String> payload = Map.of(
                "roomId", ROOM_ID,
                "lastReadMessageId", "msg-42");

        when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(false);

        chatController.markRead(payload, headerAccessor);

        verify(readReceiptService, never()).markRead(anyString(), anyString(), anyString(), anyString());
        verify(messagingTemplate).convertAndSendToUser(
                eq(USER_ID), eq("/queue/errors"), any(Map.class));
    }

    @Test
    @DisplayName("markRead returns early when roomId or lastReadMessageId is null")
    void markRead_returns_early_when_required_fields_null() {
        // Missing roomId
        Map<String, String> payload1 = new HashMap<>();
        payload1.put("roomId", null);
        payload1.put("lastReadMessageId", "msg-42");

        chatController.markRead(payload1, headerAccessor);

        verifyNoInteractions(readReceiptService);

        // Missing lastReadMessageId
        Map<String, String> payload2 = new HashMap<>();
        payload2.put("roomId", ROOM_ID);
        payload2.put("lastReadMessageId", null);

        chatController.markRead(payload2, headerAccessor);

        verifyNoInteractions(readReceiptService);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private ChatMessage buildChatMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(USER_ID);
        msg.setUsername(USERNAME);
        msg.setContent("hello");
        msg.setType(ChatMessage.MessageType.CHAT);
        return msg;
    }
}
