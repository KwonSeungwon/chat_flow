package com.chatflow.chat.config;

import com.chatflow.chat.service.room.RoomMembershipChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
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
 *   <li><b>CONNECT</b> — promotes {@code sessionAttributes.userId} to
 *       {@link Principal} so that {@code convertAndSendToUser} works.</li>
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

    private final RoomMembershipChecker membershipChecker;

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
     * CONNECT: promote sessionAttributes.userId to Principal.
     * Preserves the existing behavior from WebSocketConfig exactly.
     */
    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            String userId = (String) attrs.get("userId");
            if (userId != null && !userId.isBlank()) {
                final String uid = userId;
                accessor.setUser(() -> uid);
            }
        }
        return message;
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
