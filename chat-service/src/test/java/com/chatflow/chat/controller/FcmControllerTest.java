package com.chatflow.chat.controller;

import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.FcmNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FcmControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private FcmNotificationService service;

    @InjectMocks
    private FcmController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unsubscribeAll_calls_service() throws Exception {
        String body = objectMapper.writeValueAsString(
            Map.of("token", "x".repeat(120)));
        mockMvc.perform(post("/api/fcm/unsubscribe-all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(service).unsubscribeAll("x".repeat(120));
    }

    @Test
    void unsubscribeAll_rejects_blank_token() throws Exception {
        mockMvc.perform(post("/api/fcm/unsubscribe-all")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
}
