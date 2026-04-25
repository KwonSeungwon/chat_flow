package com.chatflow.chat.config;

import com.chatflow.common.security.SecurityKeys;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * JWT blacklist 조회 (blocking, servlet 스택).
 * gateway-service가 Redis에 등록한 blacklist 키를 chat-service에서도 검증하여
 * /ws-native?token= 직접 경로에서 무효화된 토큰 차단.
 *
 * 성능 최적화: Caffeine 단기 negative 캐시로 Redis 라운드트립 절감.
 * - non-blacklisted 결과만 캐시 (positive는 캐시 안 함 — 즉시 invalidate 보장)
 * - Redis 장애 시 fail-open (가용성 우선)
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private final Cache<String, Boolean> negativeCache;

    public TokenBlacklistService(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${chatflow.security.blacklist.cache-ttl-seconds:5}") long cacheTtlSeconds,
            @Value("${chatflow.security.blacklist.cache-max-size:10000}") long cacheMaxSize) {
        this.redisTemplate = redisTemplate;
        this.negativeCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
                .maximumSize(cacheMaxSize)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, this.negativeCache, "blacklistNegative");
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isEmpty()) return false;

        // Negative cache hit — known not-blacklisted within TTL window
        Boolean cached = negativeCache.getIfPresent(jti);
        if (Boolean.FALSE.equals(cached)) {
            return false;
        }

        Boolean exists;
        try {
            exists = redisTemplate.hasKey(SecurityKeys.BLACKLIST_PREFIX + jti);
        } catch (Exception e) {
            log.warn("Blacklist lookup failed (fail-open): jti={}, err={}", jti, e.getMessage());
            // Fail-open: Redis 장애 시 통과 (가용성 우선).
            // 보안 trade-off — 블랙리스트 등록 직후 Redis 장애면 최대 TTL(보통 30분) 동안 허용됨.
            return false;
        }

        boolean result = Boolean.TRUE.equals(exists);
        if (!result) {
            // Only cache negative results; positive results (blacklisted) must not be cached
            // to ensure immediate token invalidation propagation.
            negativeCache.put(jti, Boolean.FALSE);
        }
        return result;
    }
}
