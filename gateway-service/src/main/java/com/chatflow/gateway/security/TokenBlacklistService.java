package com.chatflow.gateway.security;

import com.chatflow.common.security.SecurityKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 토큰을 블랙리스트에 등록 (잔여 TTL 동안만 유지)
     */
    public Mono<Boolean> blacklist(String jti, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return Mono.just(true); // 이미 만료된 토큰은 블랙리스트 불필요
        }
        return redisTemplate.opsForValue()
                .set(SecurityKeys.BLACKLIST_PREFIX + jti, "1", ttl)
                .thenReturn(true);
    }

    /**
     * 토큰이 블랙리스트에 있는지 확인
     */
    public Mono<Boolean> isBlacklisted(String jti) {
        return redisTemplate.hasKey(SecurityKeys.BLACKLIST_PREFIX + jti);
    }
}
