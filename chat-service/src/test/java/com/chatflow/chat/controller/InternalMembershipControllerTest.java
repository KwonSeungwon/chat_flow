package com.chatflow.chat.controller;

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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full security-chain tests for the service-to-service membership endpoints
 * used by search-service and ai-summary-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-for-chat-service-testing-12345",
        "GATEWAY_INTERNAL_SECRET=test-internal-secret"
})
class InternalMembershipControllerTest {

    private static final String SECRET_HEADER = "X-Gateway-Secret";
    private static final String SECRET = "test-internal-secret";

    @Autowired private MockMvc mockMvc;

    @MockBean private RoomMemberRepository roomMemberRepository;
    @MockBean private StringRedisTemplate stringRedisTemplate;
    @MockBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;

    @Test
    void internal_missingSecret_403() throws Exception {
        mockMvc.perform(get("/internal/rooms/members/u1/room-ids"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/rooms/r1/members/u1"))
                .andExpect(status().isForbidden());
        verify(roomMemberRepository, never()).findRoomIdsByUserId(anyString());
        verify(roomMemberRepository, never()).existsByRoomIdAndUserId(anyString(), anyString());
    }

    @Test
    void internal_wrongSecret_403() throws Exception {
        mockMvc.perform(get("/internal/rooms/r1/members/u1").header(SECRET_HEADER, "nope"))
                .andExpect(status().isForbidden());
        verify(roomMemberRepository, never()).existsByRoomIdAndUserId(anyString(), anyString());
    }

    @Test
    void roomIds_withSecret_returnsMemberRoomIds() throws Exception {
        when(roomMemberRepository.findRoomIdsByUserId("u1")).thenReturn(List.of("r1", "r2"));

        mockMvc.perform(get("/internal/rooms/members/u1/room-ids").header(SECRET_HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("r1"))
                .andExpect(jsonPath("$[1]").value("r2"));
    }

    @Test
    void membership_member_204() throws Exception {
        when(roomMemberRepository.existsByRoomIdAndUserId("r1", "u1")).thenReturn(true);

        mockMvc.perform(get("/internal/rooms/r1/members/u1").header(SECRET_HEADER, SECRET))
                .andExpect(status().isNoContent());
    }

    @Test
    void membership_nonMember_404() throws Exception {
        when(roomMemberRepository.existsByRoomIdAndUserId("r1", "u2")).thenReturn(false);

        mockMvc.perform(get("/internal/rooms/r1/members/u2").header(SECRET_HEADER, SECRET))
                .andExpect(status().isNotFound());
    }
}
