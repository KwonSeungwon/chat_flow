package com.gossiplog.gateway.security.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gossiplog.gateway.security.AuthService.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialLoginCodeStoreTest {

    @Mock private ReactiveStringRedisTemplate redis;
    @Mock private ReactiveValueOperations<String, String> ops;
    private SocialLoginCodeStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(ops);
        store = new SocialLoginCodeStore(redis, mapper);
    }

    @Test
    void issue_storesResponseUnderRandomCodeForSixtySeconds() throws Exception {
        AuthResponse resp = new AuthResponse("jwt", "u1", "땅콩", null);
        when(ops.set(anyString(), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(Mono.just(true));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);

        String code = store.issue(resp).block();

        verify(ops).set(key.capture(), value.capture(), eq(Duration.ofSeconds(60)));
        assertEquals("gossiplog:oauth:code:" + code, key.getValue());
        assertTrue(code.matches("^[A-Za-z0-9_-]{43}$"), code); // 32바이트 URL-safe
        assertEquals(resp, mapper.readValue(value.getValue(), AuthResponse.class));
    }

    @Test
    void redeem_isOneShot_viaGetAndDelete() throws Exception {
        AuthResponse resp = new AuthResponse("jwt", "u1", "땅콩", null);
        String code = "A".repeat(43);
        when(ops.getAndDelete("gossiplog:oauth:code:" + code)).thenReturn(Mono.just(mapper.writeValueAsString(resp)));

        StepVerifier.create(store.redeem(code)).expectNext(resp).verifyComplete();
    }

    @Test
    void redeem_unknownCode_isEmpty() {
        String code = "B".repeat(43);
        when(ops.getAndDelete("gossiplog:oauth:code:" + code)).thenReturn(Mono.empty());
        StepVerifier.create(store.redeem(code)).verifyComplete();
    }

    @Test
    void redeem_malformedCode_isEmptyWithoutTouchingRedis() {
        StepVerifier.create(store.redeem("../../x")).verifyComplete();
        StepVerifier.create(store.redeem(null)).verifyComplete();
        verify(ops, never()).getAndDelete(anyString());
    }
}
