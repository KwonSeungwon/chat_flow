package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.exception.ForbiddenException;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private MessageInteractionController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AuthenticatedUserResolver())
            .addInterceptors(new AuthInterceptor(membershipGuard))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getReplies_returns_list_with_entity_only_fields() throws Exception {
        doNothing().when(membershipGuard).requireMember("room-1", "u1");
        ChatMessageEntity reply = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT.name())
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .reactions("{\"\\uD83D\\uDC4D\":[\"u9\"]}")
            .edited(true)
            .pinned(true)
            .build();
        when(messageThreadService.findReplies("room-1", "p1")).thenReturn(List.of(reply));

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies")
                .header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].messageId").value("r1"))
            .andExpect(jsonPath("$.data[0].parentMessageId").value("p1"))
            .andExpect(jsonPath("$.data[0].reactions").exists())
            .andExpect(jsonPath("$.data[0].edited").value(true))
            .andExpect(jsonPath("$.data[0].pinned").value(true));
    }

    @Test
    void getReplies_empty_returns_empty_list() throws Exception {
        doNothing().when(membershipGuard).requireMember("room-1", "u1");
        when(messageThreadService.findReplies("room-1", "p1")).thenReturn(List.of());

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies")
                .header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getReplies_returns_401_when_user_id_missing() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies"))
            .andExpect(status().isUnauthorized());
        verify(messageThreadService, never()).findReplies(anyString(), anyString());
    }

    @Test
    void getReplies_returns_403_when_not_room_member() throws Exception {
        doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                .when(membershipGuard).requireMember("room-1", "u-outsider");

        mockMvc.perform(get("/api/chat/rooms/room-1/messages/p1/replies")
                .header("X-User-Id", "u-outsider"))
            .andExpect(status().isForbidden());
        verify(messageThreadService, never()).findReplies(anyString(), anyString());
    }
}
