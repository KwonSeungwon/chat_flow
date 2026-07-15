package com.chatflow.chat.controller;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.mapper.ScheduledMessageMapper;
import com.chatflow.chat.service.notification.ScheduledMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ScheduledMessageControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ScheduledMessageService service;

    @Mock
    private ScheduledMessageMapper scheduledMessageMapper;

    @InjectMocks
    private ScheduledMessageController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── schedule (POST) ─────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/scheduled-messages (schedule)")
    class Schedule {

        @Test
        @DisplayName("happy path — delegates to service and returns 200")
        void happy_path() throws Exception {
            LocalDateTime at = LocalDateTime.of(2026, 8, 1, 14, 30);
            ScheduledMessageEntity entity = ScheduledMessageEntity.builder()
                    .id(1L)
                    .chatRoomId("room-1")
                    .userId("user-1")
                    .username("alice")
                    .content("hello")
                    .scheduledAt(at)
                    .build();
            ScheduledMessageDto dto = new ScheduledMessageDto(
                    1L, "room-1", "hello", at, "PENDING", at);

            when(service.schedule(eq("room-1"), eq("user-1"), eq("alice"),
                    eq("hello"), eq(at)))
                    .thenReturn(entity);
            when(scheduledMessageMapper.toDto(entity)).thenReturn(dto);

            String body = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", "room-1",
                    "content", "hello",
                    "scheduledAt", "2026-08-01T14:30:00"));

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1));

            verify(service).schedule("room-1", "user-1", "alice", "hello", at);
        }

        @Test
        @DisplayName("missing chatRoomId -> 400 VALIDATION_ERROR")
        void returns_400_when_chatRoomId_missing() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "content", "hello",
                    "scheduledAt", "2026-08-01T14:30:00"));

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.chatRoomId").exists());

            verify(service, never()).schedule(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("blank chatRoomId -> 400 VALIDATION_ERROR")
        void returns_400_when_chatRoomId_blank() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", "   ",
                    "content", "hello",
                    "scheduledAt", "2026-08-01T14:30:00"));

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.chatRoomId").exists());

            verify(service, never()).schedule(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("missing content -> 400 VALIDATION_ERROR")
        void returns_400_when_content_missing() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", "room-1",
                    "scheduledAt", "2026-08-01T14:30:00"));

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.content").exists());

            verify(service, never()).schedule(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("missing scheduledAt -> 400 VALIDATION_ERROR")
        void returns_400_when_scheduledAt_missing() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", "room-1",
                    "content", "hello"));

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.scheduledAt").exists());

            verify(service, never()).schedule(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("malformed scheduledAt -> 400 INVALID_DATETIME (parse path unchanged)")
        void returns_400_invalid_datetime_when_scheduledAt_malformed() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", "room-1",
                    "content", "hello",
                    "scheduledAt", "not-a-date"));

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_DATETIME"));

            verify(service, never()).schedule(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("non-string chatRoomId in JSON -> 400 (typed record eliminates ClassCastException)")
        void returns_400_when_chatRoomId_is_number() throws Exception {
            // Previously this would bind via Map<String,Object> and the
            // unchecked (String) cast would throw ClassCastException -> 500.
            // With the typed ScheduleRequest, Jackson fails to deserialize
            // a number into String chatRoomId -> HttpMessageNotReadableException -> 400.
            // Actually, Jackson DOES coerce numbers to Strings for record
            // components, so this becomes a valid request with chatRoomId="123".
            // The ClassCastException path is eliminated by typing alone.
            String rawJson = """
                    {"chatRoomId": 123, "content": "hello", "scheduledAt": "2026-08-01T14:30:00"}
                    """;

            // Jackson coerces 123 -> "123" for String fields, so this is actually
            // a valid request. The important thing is: no 500 ClassCastException.
            LocalDateTime at = LocalDateTime.of(2026, 8, 1, 14, 30);
            ScheduledMessageEntity entity = ScheduledMessageEntity.builder()
                    .id(2L).chatRoomId("123").userId("user-1").username("alice")
                    .content("hello").scheduledAt(at).build();
            ScheduledMessageDto dto = new ScheduledMessageDto(
                    2L, "123", "hello", at, "PENDING", at);

            when(service.schedule(eq("123"), eq("user-1"), eq("alice"),
                    eq("hello"), eq(at)))
                    .thenReturn(entity);
            when(scheduledMessageMapper.toDto(entity)).thenReturn(dto);

            mockMvc.perform(post("/api/chat/scheduled-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(rawJson)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ── cancel (DELETE) — info-leak invariant ────────────────────

    @Nested
    @DisplayName("DELETE /api/chat/scheduled-messages/{id} (cancel)")
    class Cancel {

        @Test
        @DisplayName("cancel returns 404 with masked message when not owned")
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
        @DisplayName("cancel returns same response shape for not-found and not-owned")
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
}
