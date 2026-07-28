package com.chatflow.chat.event;

import com.chatflow.chat.service.UserPresenceService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageEventListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private UserPresenceService userPresenceService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private MessageEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new MessageEventListener(messagingTemplate, userPresenceService);
    }

    // ── 1. CHAT message broadcasts + fans out unread ───────────────────

    @Test
    @DisplayName("CHAT message: broadcasts to topic and fans out UNREAD_INCREMENT to non-sender participants")
    void chatMessage_broadcastsAndFansOutUnread() {
        // given
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content("hello everyone")
                .type(ChatMessage.MessageType.CHAT)
                .messageId("msg-1")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message);

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenReturn(Set.of("u-sender", "u2", "u3"));

        // when
        listener.onMessagePersisted(event);

        // then — topic broadcast happens exactly once
        verify(messagingTemplate).convertAndSend("/topic/chat/r1", message);

        // then — per-user unread sent to u2 and u3, NOT to u-sender
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate, times(2))
                .convertAndSendToUser(userIdCaptor.capture(), eq("/queue/room-updates"), payloadCaptor.capture());

        List<String> recipients = userIdCaptor.getAllValues();
        assertThat(recipients).containsExactlyInAnyOrder("u2", "u3");
        assertThat(recipients).doesNotContain("u-sender");

        // Verify payload structure
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("type", "UNREAD_INCREMENT");
        assertThat(payload).containsEntry("roomId", "r1");
        assertThat(payload).containsEntry("senderId", "u-sender");
    }

    // ── 2. Mentions ride on the event ──────────────────────────────────

    @Test
    @DisplayName("CHAT message with @mention: payload carries the usernames resolved at publish time")
    void chatMessage_withMentions_carriesResolvedUsernames() {
        // given
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content("@u2 hello @Phill Park check this")
                .type(ChatMessage.MessageType.CHAT)
                .messageId("msg-2")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event =
                new MessagePersistedEvent(message, List.of("u2", "Phill Park"));

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenReturn(Set.of("u-sender", "u2"));

        // when
        listener.onMessagePersisted(event);

        // then
        verify(messagingTemplate).convertAndSendToUser(
                eq("u2"), eq("/queue/room-updates"), payloadCaptor.capture());

        // "Phill Park" contains a space — no charset regex would survive it. The
        // listener does not parse, so whatever the sender resolved gets through.
        assertThat(payloadCaptor.getValue())
                .containsEntry("mentionedUsernames", List.of("u2", "Phill Park"));
    }

    @Test
    @DisplayName("The listener reports the carried list, never a re-parse of the content")
    void chatMessage_payloadFollowsTheCarriedList_notTheContent() {
        // given — the content names "@nobody" (no such member) and an email, while
        // the resolved list names "u2", which appears nowhere in the text.
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content("mail me at bob@example.com or ping @nobody")
                .type(ChatMessage.MessageType.CHAT)
                .messageId("msg-2b")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message, List.of("u2"));

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenReturn(Set.of("u-sender", "u2"));

        // when
        listener.onMessagePersisted(event);

        // then — any content parsing would have yielded [] or ["nobody"]
        verify(messagingTemplate).convertAndSendToUser(
                eq("u2"), eq("/queue/room-updates"), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).containsEntry("mentionedUsernames", List.of("u2"));
    }

    @Test
    @DisplayName("No resolved mentions: payload carries an empty list, not null")
    void chatMessage_withoutMentions_carriesEmptyList() {
        // given
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content("점심 뭐 먹지")
                .type(ChatMessage.MessageType.CHAT)
                .messageId("msg-2c")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message);

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenReturn(Set.of("u-sender", "u2"));

        // when
        listener.onMessagePersisted(event);

        // then
        verify(messagingTemplate).convertAndSendToUser(
                eq("u2"), eq("/queue/room-updates"), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).containsEntry("mentionedUsernames", List.of());
    }

    // ── 3. JOIN/LEAVE/SYSTEM → broadcast only, no unread ──────────────

    @ParameterizedTest(name = "{0} message: broadcast only, no unread fan-out")
    @EnumSource(value = ChatMessage.MessageType.class, names = {"JOIN", "LEAVE", "SYSTEM"})
    @DisplayName("Non-CHAT/FILE types: broadcast to topic but no unread notifications")
    void nonChatType_broadcastOnly_noUnread(ChatMessage.MessageType type) {
        // given
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content("joined the room")
                .type(type)
                .messageId("msg-3")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message);

        // when
        listener.onMessagePersisted(event);

        // then — topic broadcast still happens
        verify(messagingTemplate).convertAndSend("/topic/chat/r1", message);

        // then — no per-user notifications and no participant lookup
        verify(messagingTemplate, never())
                .convertAndSendToUser(any(), any(), any());
        verify(userPresenceService, never())
                .getRoomParticipantUserIds(any());
    }

    // ── 4a. Content truncation: content > 200 chars ────────────────────

    @Test
    @DisplayName("Content longer than 200 chars is truncated to exactly 200 in payload")
    void contentTruncation_longContent() {
        // given
        String longContent = "A".repeat(300);
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content(longContent)
                .type(ChatMessage.MessageType.CHAT)
                .messageId("msg-4")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message);

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenReturn(Set.of("u-sender", "u2"));

        // when
        listener.onMessagePersisted(event);

        // then
        verify(messagingTemplate).convertAndSendToUser(
                eq("u2"), eq("/queue/room-updates"), payloadCaptor.capture());

        String truncatedContent = (String) payloadCaptor.getValue().get("content");
        assertThat(truncatedContent).hasSize(200);
        assertThat(truncatedContent).isEqualTo("A".repeat(200));
    }

    // ── 4b. Null content (FILE type) → payload content == "" ───────────

    @Test
    @DisplayName("FILE message with null content: payload content is empty string, no NPE")
    void fileMessage_nullContent_emptyStringInPayload() {
        // given
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content(null)
                .type(ChatMessage.MessageType.FILE)
                .messageId("msg-5")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message);

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenReturn(Set.of("u-sender", "u2"));

        // when — should not throw NPE
        assertThatCode(() -> listener.onMessagePersisted(event)).doesNotThrowAnyException();

        // then
        verify(messagingTemplate).convertAndSendToUser(
                eq("u2"), eq("/queue/room-updates"), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue().get("content")).isEqualTo("");
    }

    // ── 5. Resilience: exception in participant lookup is swallowed ────

    @Test
    @DisplayName("Exception from userPresenceService is swallowed; topic broadcast still happened")
    void resilience_participantLookupThrows_exceptionSwallowed() {
        // given
        ChatMessage message = ChatMessage.builder()
                .chatRoomId("r1")
                .userId("u-sender")
                .username("sender-name")
                .content("hello")
                .type(ChatMessage.MessageType.CHAT)
                .messageId("msg-6")
                .timestamp(LocalDateTime.now())
                .build();
        MessagePersistedEvent event = new MessagePersistedEvent(message);

        when(userPresenceService.getRoomParticipantUserIds("r1"))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // when — should NOT propagate
        assertThatCode(() -> listener.onMessagePersisted(event)).doesNotThrowAnyException();

        // then — topic broadcast happened before the failure
        verify(messagingTemplate).convertAndSend("/topic/chat/r1", message);

        // then — no per-user notifications were sent (failure happened before the loop)
        verify(messagingTemplate, never())
                .convertAndSendToUser(any(), any(), any());
    }
}
