package com.chatflow.chat.config;

import com.chatflow.chat.service.ChatService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class WebSocketEventListener {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public WebSocketEventListener(ChatService chatService, SimpMessagingTemplate messagingTemplate, MeterRegistry registry) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
        Gauge.builder("chatflow.websocket.sessions", activeSessions, AtomicInteger::get)
                .description("Active WebSocket sessions")
                .register(registry);
    }

    @EventListener
    public void handleWebSocketConnect(SessionConnectedEvent event) {
        activeSessions.incrementAndGet();
    }

    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        activeSessions.decrementAndGet();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        var attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            log.debug("WebSocket disconnected with no session attributes, session={}", accessor.getSessionId());
            return;
        }
        String username = (String) attrs.get("username");
        String roomId = (String) attrs.get("chatRoomId");
        String sessionId = accessor.getSessionId();

        if (username != null && roomId != null) {
            log.info("WebSocket disconnected: user={}, room={}, session={}", username, roomId, sessionId);
            // 타이핑 중이던 사용자가 끊겼음을 방 전체에 알려 유령 인디케이터 방지
            try {
                messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/typing",
                        Map.of("username", username, "stop", true,
                                "timestamp", java.time.LocalDateTime.now().toString()));
            } catch (Exception e) {
                log.warn("Failed to broadcast typing-stop on disconnect: {}", e.getMessage());
            }
            chatService.removeUser(roomId, username, sessionId);
        }
    }
}
