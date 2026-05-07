package com.chatflow.chat.controller;

import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.ScheduledMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the cancel-404 info-leak invariant: the client must NOT be able to
 * tell whether (a) the row doesn't exist or (b) the row exists but they
 * don't own it. Both branches throw the same exception type from the
 * service; the controller responds identically. Future refactors that
 * differentiate these responses will fail this test.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledMessageControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ScheduledMessageService service;

    @InjectMocks
    private ScheduledMessageController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void cancel_returns404_withMaskedMessage_whenNotOwned() throws Exception {
        when(service.cancel(anyLong(), anyString()))
                .thenThrow(new IllegalStateException(
                        "Scheduled message not found or not owned: id=42"));

        MvcResult result = mockMvc.perform(delete("/api/chat/scheduled-messages/42")
                        .header("X-User-Id", "intruder"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Scheduled message not found");
        // Info-leak invariant: client must not learn whether the row
        // exists or who owns it.
        assertThat(body).doesNotContain("owned");
        assertThat(body).doesNotContain("permission");
        assertThat(body).doesNotContain("intruder");
    }

    @Test
    void cancel_returns404_sameResponseShape_forNotFoundAndNotOwned() throws Exception {
        // Both branches throw the SAME exception type from the service —
        // the controller masks them identically. Lock the response-shape
        // equality so a future refactor cannot regress this.
        when(service.cancel(anyLong(), anyString()))
                .thenThrow(new IllegalStateException(
                        "Scheduled message not found or not owned: id=42"));

        String bodyA = mockMvc.perform(delete("/api/chat/scheduled-messages/42")
                        .header("X-User-Id", "user-a"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String bodyB = mockMvc.perform(delete("/api/chat/scheduled-messages/42")
                        .header("X-User-Id", "user-b"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // ApiResponse.error includes a `timestamp` field that may differ
        // between the two requests by microseconds. Compare every other
        // field — success/message/data — for byte-identical content.
        JsonNode jsonA = objectMapper.readTree(bodyA);
        JsonNode jsonB = objectMapper.readTree(bodyB);

        assertThat(jsonA.get("success")).isEqualTo(jsonB.get("success"));
        assertThat(jsonA.get("message")).isEqualTo(jsonB.get("message"));
        assertThat(jsonA.get("data")).isEqualTo(jsonB.get("data"));
        // Neither response body should leak the caller's userId.
        assertThat(bodyA).doesNotContain("user-a");
        assertThat(bodyB).doesNotContain("user-b");
    }
}
