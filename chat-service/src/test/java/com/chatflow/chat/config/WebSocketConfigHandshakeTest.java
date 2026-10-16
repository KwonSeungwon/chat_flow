package com.chatflow.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketConfigHandshakeTest {

    private static final String SECRET = "test-gateway-secret";

    private final WebSocketConfig.GatewayHandshakeInterceptor interceptor =
            new WebSocketConfig.GatewayHandshakeInterceptor(SECRET);

    private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    private final Map<String, Object> attributes = new HashMap<>();

    private boolean handshake(MockHttpServletRequest request) {
        return handshake(interceptor, request);
    }

    private boolean handshake(WebSocketConfig.GatewayHandshakeInterceptor target,
                              MockHttpServletRequest request) {
        return target.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler.class), attributes);
    }

    @Test
    void handshake_missingGatewaySecret_rejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-native");
        request.addHeader("X-User-Id", "user-1");
        request.addHeader("X-Username", "alice");

        assertThat(handshake(request)).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(attributes).isEmpty();
    }

    @Test
    void handshake_wrongGatewaySecret_rejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-native");
        request.addHeader("X-Gateway-Secret", "guess");
        request.addHeader("X-User-Id", "user-1");

        assertThat(handshake(request)).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void handshake_secretNotConfigured_failsClosed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-native");
        request.addHeader("X-Gateway-Secret", "");

        assertThat(handshake(new WebSocketConfig.GatewayHandshakeInterceptor(""), request)).isFalse();
    }

    @Test
    void handshake_validSecretWithGatewayIdentity_copiesIdentityToSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-native");
        request.addHeader("X-Gateway-Secret", SECRET);
        request.addHeader("X-User-Id", "user-1");
        request.addHeader("X-Username", "%ED%99%8D%EA%B8%B8%EB%8F%99");

        assertThat(handshake(request)).isTrue();
        assertThat(attributes).containsEntry("userId", "user-1").containsEntry("username", "홍길동");
    }

    @Test
    void handshake_validSecretWithoutIdentity_acceptedForConnectTimeAuth() {
        // Browser clients cannot set handshake headers; they authenticate on STOMP CONNECT.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-native");
        request.addHeader("X-Gateway-Secret", SECRET);

        assertThat(handshake(request)).isTrue();
        assertThat(attributes).doesNotContainKey("userId");
    }
}
