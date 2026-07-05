package com.chatflow.chat.config;

import com.chatflow.chat.service.room.RoomMembershipChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock private RoomMembershipChecker membershipChecker;
    @Mock private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    private static final String ROOM_ID = "room-abc";
    private static final String USER_ID = "user-123";
    private static final String USERNAME = "alice";

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(membershipChecker);
    }

    // ── Helper: build a STOMP message with session attributes ──────

    private Message<?> buildSubscribeMessage(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("userId", USER_ID);
        sessionAttrs.put("username", USERNAME);
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("session-1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildConnectMessage(String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Map<String, Object> sessionAttrs = new HashMap<>();
        if (userId != null) {
            sessionAttrs.put("userId", userId);
            sessionAttrs.put("username", USERNAME);
        }
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("session-1");
        // Leave mutable so the interceptor can set the Principal (mirrors Spring's channel pipeline)
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // ── CONNECT tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("CONNECT")
    class ConnectTests {

        @Test
        @DisplayName("promotes userId from session attributes to Principal")
        void connect_promotesUserIdToPrincipal() {
            Message<?> msg = buildConnectMessage(USER_ID);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
            StompHeaderAccessor resultAccessor =
                    StompHeaderAccessor.wrap(result);
            Principal user = resultAccessor.getUser();
            assertThat(user).isNotNull();
            assertThat(user.getName()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("passes through when no userId in session")
        void connect_noUserId_passesThrough() {
            Message<?> msg = buildConnectMessage(null);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }
    }

    // ── SUBSCRIBE tests ────────────────────────────────────────────

    @Nested
    @DisplayName("SUBSCRIBE to /topic/chat/{roomId}")
    class SubscribeRoomTests {

        @Test
        @DisplayName("allows member to subscribe to room topic")
        void member_subscribesToRoom_allowed() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("rejects non-member subscribe to room topic (returns null)")
        void nonMember_subscribesToRoom_rejected() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(false);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("allows member to subscribe to room sub-topic (/typing)")
        void member_subscribesToTyping_allowed() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/typing");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("rejects non-member subscribe to room sub-topic (/errors)")
        void nonMember_subscribesToErrors_rejected() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(false);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/errors");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("rejects non-member subscribe to deep room sub-topic (/thread/...)")
        void nonMember_subscribesToThread_rejected() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(false);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/thread/t1");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("allows member to subscribe to read-receipts sub-topic")
        void member_subscribesToReadReceipts_allowed() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/read-receipts");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("allows member to subscribe to presence sub-topic")
        void member_subscribesToPresence_allowed() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/presence");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("allows member to subscribe to members sub-topic")
        void member_subscribesToMembers_allowed() {
            when(membershipChecker.isMember(ROOM_ID, USER_ID)).thenReturn(true);
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/members");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }
    }

    // ── Non-room SUBSCRIBE tests ───────────────────────────────────

    @Nested
    @DisplayName("SUBSCRIBE to non-room destinations")
    class SubscribeNonRoomTests {

        @Test
        @DisplayName("allows /user/queue/kicked subscription without membership check")
        void userQueue_allowed() {
            Message<?> msg = buildSubscribeMessage("/user/queue/kicked");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("allows /user/queue/muted subscription without membership check")
        void userQueueMuted_allowed() {
            Message<?> msg = buildSubscribeMessage("/user/queue/muted");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("allows /user/queue/errors subscription without membership check")
        void userQueueErrors_allowed() {
            Message<?> msg = buildSubscribeMessage("/user/queue/errors");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("allows non-chat topic subscription without membership check")
        void nonChatTopic_allowed() {
            Message<?> msg = buildSubscribeMessage("/topic/notifications");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }
    }

    // ── Edge cases ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("SUBSCRIBE to /topic/chat/ with no roomId segment passes through (malformed, no broker match)")
        void topicChatNoRoomId_passesThrough() {
            // "/topic/chat/" has a trailing slash but no roomId segment — regex requires [^/]+
            // so it doesn't match the room-topic pattern. Harmless: no broker will deliver to it.
            Message<?> msg = buildSubscribeMessage("/topic/chat/");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("SEND command passes through without membership check")
        void sendCommand_passesThrough() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/app/chat.sendMessage");
            Map<String, Object> sessionAttrs = new HashMap<>();
            sessionAttrs.put("userId", USER_ID);
            accessor.setSessionAttributes(sessionAttrs);
            Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("SUBSCRIBE with null session attributes rejects room topic")
        void nullSessionAttributes_rejectsRoomTopic() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/" + ROOM_ID);
            // Do NOT set session attributes
            accessor.setSessionId("session-1");
            Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNull();
        }
    }
}
