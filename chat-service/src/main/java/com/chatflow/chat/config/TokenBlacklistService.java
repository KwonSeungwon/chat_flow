package com.chatflow.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * JWT blacklist 조회 (blocking, servlet 스택).
 * gateway-service가 Redis에 등록한 blacklist 키를 chat-service에서도 검증하여
 * /ws-native?token= 직접 경로에서 무효화된 토큰 차단.
 */
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "chatflow:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isEmpty()) return false;
        Boolean exists = redisTemplate.hasKey(BLACKLIST_PREFIX + jti);
        return Boolean.TRUE.equals(exists);
    }
}
