package com.chatflow.chat.config;

import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kubernetes liveness/readiness probes hit the health-group paths without credentials.
 * A 401/403 here would crashloop the pod.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-for-chat-service-testing-12345",
        "GATEWAY_INTERNAL_SECRET=test-internal-secret",
        "management.endpoint.health.probes.enabled=true"
})
class SecurityConfigHealthProbeTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RoomMemberRepository roomMemberRepository;
    @MockBean private StringRedisTemplate stringRedisTemplate;
    @MockBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;

    @Test
    void livenessProbe_unauthenticated_isOpen() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    void readinessProbe_unauthenticated_isNotBlocked() throws Exception {
        // Readiness aggregates dependency health (DB/Redis/Kafka mocks), so the status
        // may be 200 or 503 — what matters is that security does not reject the request.
        int code = mockMvc.perform(get("/actuator/health/readiness"))
                .andReturn().getResponse().getStatus();
        if (code == 401 || code == 403) {
            throw new AssertionError("readiness probe rejected by security: " + code);
        }
    }
}
