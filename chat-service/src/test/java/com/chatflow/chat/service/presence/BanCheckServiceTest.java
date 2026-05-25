package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.RoomBanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BanCheckServiceTest {

    @Mock RoomBanService roomBanService;
    @Mock SimpMessagingTemplate messagingTemplate;

    private BanCheckService banCheckService;

    @BeforeEach
    void setUp() {
        banCheckService = new BanCheckService(roomBanService, messagingTemplate);
    }

    @Test
    void empty_userId_passes_gate() {
        assertThat(banCheckService.checkBanGate("", "room-1", "anon")).isFalse();
        verifyNoInteractions(roomBanService);
    }

    @Test
    void non_banned_user_passes_gate() {
        when(roomBanService.isBanned("room-1", "user-1")).thenReturn(false);
        assertThat(banCheckService.checkBanGate("user-1", "room-1", "alice")).isFalse();
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void banned_user_is_blocked_and_error_broadcast() {
        when(roomBanService.isBanned("room-1", "user-1")).thenReturn(true);
        assertThat(banCheckService.checkBanGate("user-1", "room-1", "alice")).isTrue();
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/room-1/errors"),
                eq(Map.of("type", "ROOM_BANNED", "roomId", "room-1")));
    }
}
