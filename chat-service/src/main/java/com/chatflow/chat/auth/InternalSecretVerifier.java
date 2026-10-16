package com.chatflow.chat.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies the shared internal secret ({@code X-Gateway-Secret}) on
 * service-to-service calls (search-service / ai-summary-service →
 * chat-service {@code /internal/**}). Same secret the gateway forwards.
 *
 * <p>Fail-closed: an unset secret rejects every call.
 */
@Slf4j
@Component
public class InternalSecretVerifier {

    public static final String HEADER = "X-Gateway-Secret";

    private final byte[] expected;

    public InternalSecretVerifier(@Value("${GATEWAY_INTERNAL_SECRET:}") String secret) {
        this.expected = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String incoming) {
        if (expected.length == 0) {
            log.error("GATEWAY_INTERNAL_SECRET이 설정되지 않음 — 내부 호출 거부 (fail-closed)");
            return false;
        }
        if (incoming == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, incoming.getBytes(StandardCharsets.UTF_8));
    }
}
