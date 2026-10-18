package com.gossiplog.chat.config;

import com.gossiplog.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kubernetes liveness/readiness probes hit the health-group paths without credentials.
 * A 401/403 here would crashloop the pod. Prometheus scrapes /actuator/prometheus
 * the same way (in-cluster only — ingress and gateway never route /actuator).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false) // Boot disables metrics export in tests; the scrape endpoint needs it
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-for-chat-service-testing-12345",
        "GATEWAY_INTERNAL_SECRET=test-internal-secret",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus"
})
class SecurityConfigHealthProbeTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RoomMemberRepository roomMemberRepository;
    @MockBean private StringRedisTemplate stringRedisTemplate;

    @Test
    void livenessProbe_unauthenticated_isOpen() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    void readinessProbe_unauthenticated_isNotBlocked() throws Exception {
        // Readiness aggregates dependency health (DB/Redis mocks), so the status
        // may be 200 or 503 — what matters is that security does not reject the request.
        int code = mockMvc.perform(get("/actuator/health/readiness"))
                .andReturn().getResponse().getStatus();
        if (code == 401 || code == 403) {
            throw new AssertionError("readiness probe rejected by security: " + code);
        }
    }

    @Test
    void prometheusScrape_unauthenticated_isOpen() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
