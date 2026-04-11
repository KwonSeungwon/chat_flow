package com.chatflow.chat.config;

import com.chatflow.chat.service.ChatService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class WebSocketEventListener {

    private final ChatService chatService;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public WebSocketEventListener(ChatService chatService, MeterRegistry registry) {
        this.chatService = chatService;
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
        String username = (String) accessor.getSessionAttributes().get("username");
        String roomId = (String) accessor.getSessionAttributes().get("chatRoomId");
        String sessionId = accessor.getSessionId();

        if (username != null && roomId != null) {
            log.info("WebSocket disconnected: user={}, room={}, session={}", username, roomId, sessionId);
            chatService.removeUser(roomId, username, sessionId);
        }
    }
}
