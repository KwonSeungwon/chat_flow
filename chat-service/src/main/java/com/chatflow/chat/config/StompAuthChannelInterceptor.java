package com.chatflow.chat.config;

import com.chatflow.chat.service.room.RoomMembershipChecker;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound STOMP channel interceptor that handles:
 * <ol>
 *   <li><b>CONNECT</b> — establishes the session identity and promotes it to
 *       {@link Principal} so that {@code convertAndSendToUser} works. The
 *       identity comes from the gateway-verified handshake
 *       ({@code sessionAttributes.userId}) or, when the handshake carried none
 *       (browser clients cannot set handshake headers), from a native STOMP
 *       header {@code Authorization: Bearer <jwt>} validated with the same
 *       secret and blacklist the REST path uses. A CONNECT with neither is
 *       rejected (ERROR frame + session close).</li>
 *   <li><b>SUBSCRIBE</b> — authorizes room-topic subscriptions against
 *       {@link RoomMembershipChecker}. Non-members silently have their
 *       SUBSCRIBE frame dropped (returns {@code null}) rather than throwing,
 *       to avoid killing the WebSocket session on edge-case timing issues.</li>
 * </ol>
 *
 * <p>Non-room destinations ({@code /user/**}, {@code /queue/**}, other
 * {@code /topic/*} that don't match {@code /topic/chat/{roomId}...}) pass
 * through without a membership check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final RoomMembershipChecker membershipChecker;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * Matches {@code /topic/chat/{roomId}} and any sub-path
     * (e.g. {@code /topic/chat/room-1/typing}, {@code /topic/chat/room-1/thread/t1}).
     * Capture group 1 = roomId.
     */
    private static final Pattern ROOM_TOPIC_PATTERN =
            Pattern.compile("^/topic/chat/([^/]+)(?:/.*)?$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == null) return message;

        return switch (command) {
            case CONNECT -> handleConnect(message, accessor);
            case SUBSCRIBE -> handleSubscribe(message, accessor);
            default -> message;
        };
    }

    /**
     * CONNECT: session identity from the handshake wins; otherwise a valid
     * Bearer token in the native {@code Authorization} header; otherwise reject.
     */
    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String userId = attrs != null ? (String) attrs.get("userId") : null;
        if (userId == null || userId.isBlank()) {
            Claims claims = authenticateBearer(accessor);
            if (claims == null) {
                log.warn("STOMP CONNECT rejected: no gateway identity and no valid Bearer token (session={})",
                        accessor.getSessionId());
                throw new MessageDeliveryException(message, "Unauthorized");
            }
            userId = claims.getSubject();
            if (attrs != null) {
                String username = claims.get("username", String.class);
                attrs.put("userId", userId);
                attrs.put("username", username != null && !username.isBlank() ? username : userId);
            }
        }
        final String uid = userId;
        accessor.setUser(() -> uid);
        return message;
    }

    /** @return verified, non-blacklisted claims with a subject, or {@code null}. */
    private Claims authenticateBearer(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null) {
            header = accessor.getFirstNativeHeader("authorization");
        }
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        Claims claims;
        try {
            claims = jwtUtil.parseToken(header.substring(BEARER_PREFIX.length()).trim());
        } catch (Exception e) {
            log.debug("STOMP CONNECT JWT 검증 실패: {}", e.getMessage());
            return null;
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            return null;
        }
        String jti = claims.getId();
        if (jti != null && tokenBlacklistService.isBlacklisted(jti)) {
            log.debug("STOMP CONNECT blacklisted token rejected: jti={}", jti);
            return null;
        }
        return claims;
    }

    /**
     * SUBSCRIBE: authorize room-topic destinations against membership.
     * Returns {@code null} to silently drop the frame if the user is not a member.
     */
    private Message<?> handleSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) return message;

        Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            // Not a room topic — /user/queue/..., /topic/notifications, etc. Allow.
            return message;
        }

        String roomId = matcher.group(1);
        if (roomId.isBlank()) {
            log.warn("STOMP SUBSCRIBE rejected: empty roomId in destination={}", destination);
            return null;
        }

        String userId = extractUserId(accessor);
        if (userId == null || userId.isBlank()) {
            log.warn("STOMP SUBSCRIBE rejected: no userId in session for destination={}", destination);
            return null;
        }

        if (!membershipChecker.isMember(roomId, userId)) {
            log.warn("STOMP SUBSCRIBE rejected: non-member user={} room={} destination={}",
                    userId, roomId, destination);
            return null;
        }

        return message;
    }

    private String extractUserId(StompHeaderAccessor accessor) {
        // Try Principal first (set during CONNECT)
        Principal user = accessor.getUser();
        if (user != null && user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        // Fall back to session attributes (always available from handshake)
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            return (String) attrs.get("userId");
        }
        return null;
    }
}
