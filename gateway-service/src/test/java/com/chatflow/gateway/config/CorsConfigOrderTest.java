package com.chatflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsConfigOrderTest {

    @Test
    void corsFilter_runsBeforeRateLimitFilter() throws Exception {
        int cors = CorsConfig.class.getMethod("corsWebFilter", List.class).getAnnotation(Order.class).value();
        int rateLimit = LoginRateLimitFilter.class.getAnnotation(Order.class).value();
        assertTrue(cors < rateLimit, "429 responses must carry CORS headers");
    }
}
