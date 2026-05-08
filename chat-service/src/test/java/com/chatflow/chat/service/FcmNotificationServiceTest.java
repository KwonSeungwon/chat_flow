package com.chatflow.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmNotificationServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;

    @InjectMocks
    private FcmNotificationService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void subscribeToRoom_adds_room_to_token_set() {
        service.subscribeToRoom("tok-abc", "room-1");
        verify(setOps).add(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-abc", "room-1");
    }

    @Test
    void unsubscribeFromRoom_removes_room_from_token_set() {
        service.unsubscribeFromRoom("tok-abc", "room-1");
        verify(setOps).remove(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-abc", "room-1");
    }

    @Test
    void unsubscribeAll_with_no_rooms_is_noop() {
        when(setOps.members(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-abc")).thenReturn(Set.of());
        service.unsubscribeAll("tok-abc");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void unsubscribeAll_removes_all_rooms_and_deletes_set() {
        when(setOps.members(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-abc"))
            .thenReturn(Set.of("room-1", "room-2"));
        service.unsubscribeAll("tok-abc");
        verify(redisTemplate).delete(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-abc");
    }

    @Test
    void unsubscribeAll_with_null_members_is_noop() {
        when(setOps.members(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-abc")).thenReturn(null);
        service.unsubscribeAll("tok-abc");
        verify(redisTemplate, never()).delete(anyString());
    }
}
