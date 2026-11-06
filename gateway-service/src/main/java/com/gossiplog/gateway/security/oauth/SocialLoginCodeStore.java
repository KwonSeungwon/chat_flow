package com.gossiplog.gateway.security.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gossiplog.gateway.security.AuthService.AuthResponse;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 소셜 로그인 성공 뒤 JWT를 URL에 싣지 않으려고 쓰는 일회용 코드. 60초 동안 한 번만 바꿀 수 있다(GETDEL).
 */
@Component
public class SocialLoginCodeStore {

    private static final String KEY_PREFIX = "gossiplog:oauth:code:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;

    public SocialLoginCodeStore(ReactiveStringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Mono<String> issue(AuthResponse response) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String json;
        try {
            json = mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        return redis.opsForValue().set(KEY_PREFIX + code, json, TTL).thenReturn(code);
    }

    public Mono<AuthResponse> redeem(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return Mono.empty();
        }
        return redis.opsForValue().getAndDelete(KEY_PREFIX + code)
                .flatMap(json -> Mono.fromCallable(() -> mapper.readValue(json, AuthResponse.class)));
    }
}
