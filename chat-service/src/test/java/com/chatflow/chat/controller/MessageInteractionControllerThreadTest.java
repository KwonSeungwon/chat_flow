package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.LinkPreviewService;
import com.chatflow.chat.service.MessageEditService;
import com.chatflow.chat.service.MessagePinService;
import com.chatflow.chat.service.MessageReactionService;
import com.chatflow.chat.service.MessageThreadService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MessageInteractionControllerThreadTest {

    private MockMvc mockMvc;

    @Mock private MessageEditService messageEditService;
    @Mock private MessageReactionService messageReactionService;
    @Mock private MessagePinService messagePinService;
    @Mock private LinkPreviewService linkPreviewService;
    @Mock private MessageThreadService messageThreadService;

    @InjectMocks
    private MessageInteractionController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getReplies_returns_list_with_entity_only_fields() throws Exception {
        ChatMessageEntity reply = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT.name())
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .reactions("{\"\\uD83D\\uDC4D\":[\"u9\"]}")
            .edited(true)
            .pinned(true)
            .build();
        when(messageThreadService.findReplies("p1")).thenReturn(List.of(reply));

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].messageId").value("r1"))
            .andExpect(jsonPath("$.data[0].parentMessageId").value("p1"))
            // Entity-only fields must be present in the JSON response —
            // these are the reason we return entity directly.
            .andExpect(jsonPath("$.data[0].reactions").exists())
            .andExpect(jsonPath("$.data[0].edited").value(true))
            .andExpect(jsonPath("$.data[0].pinned").value(true));
    }

    @Test
    void getReplies_empty_returns_empty_list() throws Exception {
        when(messageThreadService.findReplies("p1")).thenReturn(List.of());

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0));
    }
}
