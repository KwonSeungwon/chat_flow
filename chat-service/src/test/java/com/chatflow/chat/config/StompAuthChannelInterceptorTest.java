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
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock private RoomMembershipChecker membershipChecker;
    @Mock private MessageChannel channel;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private StompAuthChannelInterceptor interceptor;

    private static final String ROOM_ID = "room-abc";
    private static final String USER_ID = "user-123";
    private static final String USERNAME = "alice";
    private static final String JWT_SECRET = "test-secret-for-chat-service-testing-12345";

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(
                membershipChecker, new JwtUtil(JWT_SECRET), tokenBlacklistService);
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

    private Message<?> buildConnectMessageWithAuthorization(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setSessionId("session-1");
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static String token(String secret, String subject, String username, String jti,
                                Date expiresAt) {
        return Jwts.builder()
                .subject(subject)
                .claim("username", username)
                .id(jti)
                .issuedAt(new Date())
                .expiration(expiresAt)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static Date inOneHour() {
        return new Date(System.currentTimeMillis() + 3_600_000L);
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
        @DisplayName("session identity wins — an Authorization header is not consulted")
        void connect_sessionIdentityPresent_headerIgnored() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            Map<String, Object> sessionAttrs = new HashMap<>();
            sessionAttrs.put("userId", USER_ID);
            sessionAttrs.put("username", USERNAME);
            accessor.setSessionAttributes(sessionAttrs);
            accessor.setSessionId("session-1");
            accessor.setNativeHeader("Authorization", "Bearer garbage");
            accessor.setLeaveMutable(true);
            Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(StompHeaderAccessor.wrap(result).getUser().getName()).isEqualTo(USER_ID);
            verifyNoInteractions(tokenBlacklistService);
        }

        @Test
        @DisplayName("valid Bearer header sets Principal and session identity")
        void connect_withValidBearerHeader_setsPrincipal() {
            String jwt = token(JWT_SECRET, USER_ID, USERNAME, "jti-1", inOneHour());
            when(tokenBlacklistService.isBlacklisted("jti-1")).thenReturn(false);
            Message<?> msg = buildConnectMessageWithAuthorization("Bearer " + jwt);

            Message<?> result = interceptor.preSend(msg, channel);

            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            assertThat(resultAccessor.getUser()).isNotNull();
            assertThat(resultAccessor.getUser().getName()).isEqualTo(USER_ID);
            assertThat(resultAccessor.getSessionAttributes())
                    .containsEntry("userId", USER_ID)
                    .containsEntry("username", USERNAME);
        }

        @Test
        @DisplayName("no session identity and no Authorization header — CONNECT rejected")
        void connect_withoutAnyIdentity_rejected() {
            Message<?> msg = buildConnectMessage(null);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("token signed with another secret — CONNECT rejected")
        void connect_withForgedToken_rejected() {
            String jwt = token("some-other-secret-that-is-at-least-32-bytes!!", USER_ID, USERNAME,
                    "jti-2", inOneHour());
            Message<?> msg = buildConnectMessageWithAuthorization("Bearer " + jwt);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(MessagingException.class);
            assertThat(StompHeaderAccessor.wrap(msg).getSessionAttributes()).doesNotContainKey("userId");
        }

        @Test
        @DisplayName("expired token — CONNECT rejected")
        void connect_withExpiredToken_rejected() {
            String jwt = token(JWT_SECRET, USER_ID, USERNAME, "jti-3",
                    new Date(System.currentTimeMillis() - 60_000L));
            Message<?> msg = buildConnectMessageWithAuthorization("Bearer " + jwt);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("blacklisted (logged-out) token — CONNECT rejected")
        void connect_withBlacklistedToken_rejected() {
            String jwt = token(JWT_SECRET, USER_ID, USERNAME, "jti-4", inOneHour());
            when(tokenBlacklistService.isBlacklisted("jti-4")).thenReturn(true);
            Message<?> msg = buildConnectMessageWithAuthorization("Bearer " + jwt);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("non-Bearer Authorization header — CONNECT rejected")
        void connect_withNonBearerHeader_rejected() {
            Message<?> msg = buildConnectMessageWithAuthorization("Basic dXNlcjpwYXNz");

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(MessagingException.class);
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
        @DisplayName("allows /user/queue/room-updates subscription without membership check")
        void userQueueRoomUpdates_allowed() {
            Message<?> msg = buildSubscribeMessage("/user/queue/room-updates");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNotNull();
            verifyNoInteractions(membershipChecker);
        }

        @Test
        @DisplayName("rejects non-chat topic subscription (not on the allow-list)")
        void nonChatTopic_rejected() {
            Message<?> msg = buildSubscribeMessage("/topic/notifications");

            assertThat(interceptor.preSend(msg, channel)).isNull();
        }

        @Test
        @DisplayName("rejects wildcard and direct broker subscriptions")
        void wildcardOrDirectBroker_rejected() {
            for (String dest : new String[]{
                    "/topic/**", "/topic/*", "/topic/chat/**", "/topic/chat/*",
                    "/topic/chat/" + ROOM_ID + "/**", "/topic/chat/" + ROOM_ID + "/*",
                    "/topic/chat/{roomId}", "/topic/chat/room?", "/queue/errors",
                    "/queue/**", "/user/queue/*", "/user/**", "/app/chat.sendMessage"}) {
                assertThat(interceptor.preSend(buildSubscribeMessage(dest), channel))
                        .as(dest).isNull();
            }
            verifyNoInteractions(membershipChecker);
        }

        @Test
        @DisplayName("rejects member subscription to an unknown room sub-topic")
        void unknownRoomSubTopic_rejected() {
            Message<?> msg = buildSubscribeMessage("/topic/chat/" + ROOM_ID + "/thread/t1");

            assertThat(interceptor.preSend(msg, channel)).isNull();
        }
    }

    // ── Edge cases ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("SUBSCRIBE to /topic/chat/ with no roomId segment is rejected")
        void topicChatNoRoomId_rejected() {
            Message<?> msg = buildSubscribeMessage("/topic/chat/");

            assertThat(interceptor.preSend(msg, channel)).isNull();
        }

        @Test
        @DisplayName("SEND to a broker destination (/topic, /queue, /user) is dropped")
        void sendToBrokerDestination_dropped() {
            for (String dest : new String[]{"/topic/chat/" + ROOM_ID, "/queue/errors",
                    "/user/" + USER_ID + "/queue/kicked", "/topic/chat/" + ROOM_ID + "/typing"}) {
                StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
                accessor.setDestination(dest);
                accessor.setSessionAttributes(new HashMap<>(Map.of("userId", USER_ID)));
                Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

                assertThat(interceptor.preSend(msg, channel)).as(dest).isNull();
            }
        }

        @Test
        @DisplayName("SEND without a destination is dropped")
        void sendWithoutDestination_dropped() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThat(interceptor.preSend(msg, channel)).isNull();
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
