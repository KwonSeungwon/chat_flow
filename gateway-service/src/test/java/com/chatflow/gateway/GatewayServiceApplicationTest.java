package com.chatflow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GatewayServiceApplicationTest {

    @MockBean
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @Test
    void contextLoads() {
        // Verifies Spring application context loads successfully
    }
}
