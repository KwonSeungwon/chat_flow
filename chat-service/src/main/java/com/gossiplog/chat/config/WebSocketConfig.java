package com.gossiplog.chat.config;

import com.gossiplog.chat.auth.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${GATEWAY_INTERNAL_SECRET:}")
    private String gatewayInternalSecret;

    @Value("${gossiplog.allowed-origins:https://app.gossiplog.com,http://localhost:*,http://127.0.0.1:*}")
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
     * Registers the {@link StompAuthChannelInterceptor} which handles:
     * <ul>
     *   <li>CONNECT — promotes sessionAttributes.userId to Principal</li>
     *   <li>SUBSCRIBE — authorizes room-topic subscriptions against membership</li>
     * </ul>
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
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
        HandshakeInterceptor headersInterceptor = new GatewayHandshakeInterceptor(gatewayInternalSecret);

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

    /**
     * Accepts only handshakes that came through the gateway (valid
     * {@code X-Gateway-Secret}, constant-time compared, fail-closed when unset)
     * and copies the gateway-injected identity, if any, into the session.
     * A handshake without identity is still accepted: browser clients cannot set
     * handshake headers and authenticate on STOMP CONNECT instead
     * ({@link StompAuthChannelInterceptor}).
     */
    @Slf4j
    static final class GatewayHandshakeInterceptor implements HandshakeInterceptor {

        private final String gatewayInternalSecret;

        GatewayHandshakeInterceptor(String gatewayInternalSecret) {
            this.gatewayInternalSecret = gatewayInternalSecret;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (!(request instanceof ServletServerHttpRequest servletRequest)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            HttpServletRequest http = servletRequest.getServletRequest();
            if (!AuthHeaders.gatewaySecretMatches(gatewayInternalSecret,
                    http.getHeader(AuthHeaders.X_GATEWAY_SECRET))) {
                log.warn("WebSocket handshake rejected: missing or invalid X-Gateway-Secret (uri={})",
                        http.getRequestURI());
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            String userId = http.getHeader(AuthHeaders.X_USER_ID);
            if (userId != null && !userId.isBlank()) {
                attributes.put("userId", userId);
                attributes.put("username", AuthHeaders.decodeUsername(http.getHeader(AuthHeaders.X_USERNAME)));
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
