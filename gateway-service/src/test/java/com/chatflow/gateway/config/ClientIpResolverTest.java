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
    void resolve_ignoresXRealIp_becauseItIsTheProxyAddress() {
        // frontend nginx가 X-Real-IP를 자기 $remote_addr(traefik)로 덮어쓴다 → 모든 사용자가 한 버킷이 된다.
        var req = MockServerHttpRequest.get("/")
                .header("X-Real-IP", "10.0.0.2")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1))
                .build();
        assertEquals("10.0.0.1", ClientIpResolver.resolve(req));
    }

    @Test
    void resolve_withoutCfHeader_usesFirstXForwardedForValue() {
        var req = MockServerHttpRequest.get("/")
                .header("X-Forwarded-For", "198.51.100.9, 10.0.0.2")
                .header("X-Real-IP", "10.0.0.2")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1))
                .build();
        assertEquals("198.51.100.9", ClientIpResolver.resolve(req));
    }

    @Test
    void resolve_cfHeaderWinsOverXForwardedFor() {
        var req = MockServerHttpRequest.get("/")
                .header("CF-Connecting-IP", "203.0.113.7")
                .header("X-Forwarded-For", "6.6.6.6")
                .build();
        assertEquals("203.0.113.7", ClientIpResolver.resolve(req));
    }

    @Test
    void resolve_fallsBackToRemoteAddress_thenUnknown() {
        var withRemote = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1))
                .build();
        assertEquals("10.0.0.1", ClientIpResolver.resolve(withRemote));
        assertEquals("unknown", ClientIpResolver.resolve(MockServerHttpRequest.get("/").build()));
    }
}
