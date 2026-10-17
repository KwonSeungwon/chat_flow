package com.chatflow.gateway.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** application-prod.yml 의 서킷 브레이커·타임리미터 계약을 고정한다. */
class ProdGatewayConfigTest {

    private static PropertySource<?> prod;

    @BeforeAll
    static void load() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"));
        prod = sources.get(0);
    }

    private static Object prop(String key) {
        return prod.getProperty(key);
    }

    @Test
    void everyCircuitBreakerRouteCountsOnly502_503_504() {
        int checked = 0;
        for (int i = 0; i < 20; i++) {
            String base = "spring.cloud.gateway.routes[" + i + "].filters";
            for (int f = 0; f < 5; f++) {
                if ("CircuitBreaker".equals(prop(base + "[" + f + "].name"))) {
                    assertEquals("502,503,504", prop(base + "[" + f + "].args.statusCodes"),
                            "route " + i + " filter " + f);
                    checked++;
                }
            }
        }
        assertEquals(4, checked, "chat, fcm, game, file routes");
    }

    @Test
    void fileTimeLimiterCoversUploadLimit() {
        assertEquals("120s", prop("resilience4j.timelimiter.instances.fileCircuitBreaker.timeout-duration"));
        assertNotNull(prop("resilience4j.timelimiter.configs.default.timeout-duration"));
    }
}
