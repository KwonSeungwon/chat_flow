package com.chatflow.gateway.config;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * 신뢰 가능한 클라이언트 IP 해석.
 *
 * 운영 경로는 Cloudflare → nginx → gateway 이며 Cloudflare가 항상 CF-Connecting-IP를 설정한다.
 * X-Forwarded-For는 클라이언트가 임의로 주입·회전할 수 있으므로 절대 신뢰하지 않는다.
 */
public final class ClientIpResolver {

    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String X_REAL_IP = "X-Real-IP";

    private ClientIpResolver() {
    }

    /** Order: CF-Connecting-IP → X-Real-IP → remoteAddress. X-Forwarded-For is never trusted. */
    public static String resolve(ServerHttpRequest request) {
        String cf = request.getHeaders().getFirst(CF_CONNECTING_IP);
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String real = request.getHeaders().getFirst(X_REAL_IP);
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
