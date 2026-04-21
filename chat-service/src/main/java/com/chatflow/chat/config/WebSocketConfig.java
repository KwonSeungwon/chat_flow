package com.chatflow.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${chatflow.allowed-origins:https://app.chatflow.ai.kr,http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(heartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
        // /user/** destination resolver — convertAndSendToUser(userId, ...) 에서 사용
        config.setUserDestinationPrefix("/user");
    }

    /**
     * STOMP CONNECT 프레임 시 sessionAttributes.userId를 Principal로 승격.
     * SimpMessagingTemplate.convertAndSendToUser(userId, ...) 가 올바른 세션에 전달되도록 필수.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    Map<String, Object> attrs = accessor.getSessionAttributes();
                    if (attrs != null) {
                        String userId = (String) attrs.get("userId");
                        if (userId != null && !userId.isBlank()) {
                            final String userIdFinal = userId;
                            Principal principal = new Principal() {
                                @Override public String getName() { return userIdFinal; }
                            };
                            accessor.setUser(principal);
                        }
                    }
                }
                return message;
            }
        });
    }

    private TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        HandshakeInterceptor headersInterceptor = new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    String userId = servletRequest.getServletRequest().getHeader("X-User-Id");
                    String username = servletRequest.getServletRequest().getHeader("X-Username");
                    if (username != null) {
                        try { username = java.net.URLDecoder.decode(username, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) {}
                    }
                    if (userId != null) {
                        attributes.put("userId", userId);
                        attributes.put("username", username);
                    }
                }
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler wsHandler, Exception exception) {
            }
        };

        // SockJS endpoint (기존 React 웹 클라이언트 호환)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .addInterceptors(headersInterceptor)
                .withSockJS();

        // Native WebSocket endpoint (Flutter 클라이언트용 — SockJS 미지원)
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .addInterceptors(headersInterceptor);
    }
}
