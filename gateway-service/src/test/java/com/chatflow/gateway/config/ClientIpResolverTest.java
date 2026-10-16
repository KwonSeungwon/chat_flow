package com.chatflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    @Test
    void resolve_prefersCfConnectingIp() {
        var req = MockServerHttpRequest.get("/")
                .header("CF-Connecting-IP", "203.0.113.7")
                .header("X-Real-IP", "10.0.0.2")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1))
                .build();
        assertEquals("203.0.113.7", ClientIpResolver.resolve(req));
    }

    @Test
    void resolve_ignoresSpoofedXForwardedFor() {
        var req = MockServerHttpRequest.get("/")
                .header("X-Forwarded-For", "6.6.6.6")
                .header("X-Real-IP", "10.0.0.2")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1))
                .build();
        assertEquals("10.0.0.2", ClientIpResolver.resolve(req));
    }

    @Test
    void resolve_fallsBackToRemoteAddress_thenUnknown() {
        var withRemote = MockServerHttpRequest.get("/")
                .header("X-Forwarded-For", "6.6.6.6")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1))
                .build();
        assertEquals("10.0.0.1", ClientIpResolver.resolve(withRemote));
        assertEquals("unknown", ClientIpResolver.resolve(MockServerHttpRequest.get("/").build()));
    }
}
