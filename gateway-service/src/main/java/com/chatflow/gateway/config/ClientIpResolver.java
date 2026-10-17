package com.chatflow.gateway.config;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * 클라이언트 IP 해석.
 *
 * 운영 경로: Cloudflare 터널 → frontend nginx(ClusterIP) → gateway.
 * - CF-Connecting-IP: Cloudflare가 항상 설정(클라이언트가 보낸 값은 덮어쓴다). nginx가 그대로 전달하므로
 *   gateway까지 실제 클라이언트 IP로 도착한다. 가장 신뢰할 수 있다.
 * - X-Real-IP는 쓰지 않는다. frontend nginx가 자기 $remote_addr로 덮어써서
 *   모든 사용자가 같은 레이트 리밋 버킷을 공유하게 된다.
 * - X-Forwarded-For 첫 값: CF 헤더가 없는 경로(터널 우회·로컬 개발)의 대체값. nginx가 클라이언트가
 *   보낸 XFF 뒤에 덧붙이기만 해서 첫 값은 위조될 수 있으나, Cloudflare 경유 운영 트래픽은 CF 헤더가 먼저 쓰인다.
 */
public final class ClientIpResolver {

    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    /** Order: CF-Connecting-IP → first X-Forwarded-For → remoteAddress. */
    public static String resolve(ServerHttpRequest request) {
        String cf = request.getHeaders().getFirst(CF_CONNECTING_IP);
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String xff = request.getHeaders().getFirst(X_FORWARDED_FOR);
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
