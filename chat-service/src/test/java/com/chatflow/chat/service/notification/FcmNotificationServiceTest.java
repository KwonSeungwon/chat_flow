package com.chatflow.chat.service.notification;

import com.chatflow.chat.config.AsyncConfig;
import com.chatflow.chat.event.RoomMemberRemovedEvent;
import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmNotificationServiceTest {

    private static final String TOKEN = "tok-abc";
    private static final String USER = "user-1";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private FirebaseMessaging messaging;

    @InjectMocks
    private FcmNotificationService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // @InjectMocks may field-inject the FirebaseMessaging mock; default is "FCM disabled".
        ReflectionTestUtils.setField(service, "messaging", null);
    }

    private void enableFirebase() {
        ReflectionTestUtils.setField(service, "messaging", messaging);
    }

    @Test
    void subscribeToRoom_adds_room_to_token_set() {
        service.subscribeToRoom(TOKEN, "room-1", USER);
        verify(setOps).add(FcmNotificationService.ROOMS_KEY_PREFIX + TOKEN, "room-1");
    }

    @Test
    void subscribeToRoom_records_user_to_token_mapping() {
        service.subscribeToRoom(TOKEN, "room-1", USER);

        verify(setOps).add(FcmNotificationService.USER_TOKENS_KEY_PREFIX + USER, TOKEN);
        verify(valueOps).getAndSet(FcmNotificationService.TOKEN_OWNER_KEY_PREFIX + TOKEN, USER);
    }

    @Test
    void subscribeToRoom_tokenMovedToAnotherUser_detachesFromPreviousOwner() {
        when(valueOps.getAndSet(FcmNotificationService.TOKEN_OWNER_KEY_PREFIX + TOKEN, USER))
                .thenReturn("previous-user");

        service.subscribeToRoom(TOKEN, "room-1", USER);

        verify(setOps).remove(FcmNotificationService.USER_TOKENS_KEY_PREFIX + "previous-user", TOKEN);
    }

    @Test
    void unsubscribeFromRoom_removes_room_from_token_set() {
        service.unsubscribeFromRoom(TOKEN, "room-1");
        verify(setOps).remove(FcmNotificationService.ROOMS_KEY_PREFIX + TOKEN, "room-1");
    }

    @Test
    void unsubscribeAll_with_no_rooms_is_noop() {
        when(setOps.members(FcmNotificationService.ROOMS_KEY_PREFIX + TOKEN)).thenReturn(Set.of());
        service.unsubscribeAll(TOKEN);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void unsubscribeAll_removes_all_rooms_and_deletes_set() {
        when(setOps.members(FcmNotificationService.ROOMS_KEY_PREFIX + TOKEN))
            .thenReturn(Set.of("room-1", "room-2"));
        service.unsubscribeAll(TOKEN);
        verify(redisTemplate).delete(FcmNotificationService.ROOMS_KEY_PREFIX + TOKEN);
    }

    @Test
    void unsubscribeAll_with_null_members_is_noop() {
        when(setOps.members(FcmNotificationService.ROOMS_KEY_PREFIX + TOKEN)).thenReturn(null);
        service.unsubscribeAll(TOKEN);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void kick_unsubscribesUserTokensFromRoomTopic() {
        enableFirebase();
        when(setOps.members(FcmNotificationService.USER_TOKENS_KEY_PREFIX + "target-1"))
                .thenReturn(Set.of("tok-phone", "tok-web"));
        when(valueOps.get(FcmNotificationService.TOKEN_OWNER_KEY_PREFIX + "tok-phone")).thenReturn("target-1");
        when(valueOps.get(FcmNotificationService.TOKEN_OWNER_KEY_PREFIX + "tok-web")).thenReturn("target-1");

        service.onRoomMemberRemoved(new RoomMemberRemovedEvent(
                "room-9", "target-1", RoomMemberRemovedEvent.Reason.KICKED));

        verify(messaging).unsubscribeFromTopicAsync(
                argThat(tokens -> tokens.size() == 2
                        && tokens.containsAll(List.of("tok-phone", "tok-web"))),
                eq("room-room-9"));
        verify(setOps).remove(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-phone", "room-9");
        verify(setOps).remove(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-web", "room-9");
    }

    @Test
    void removal_skipsTokensNowOwnedByAnotherUser() {
        enableFirebase();
        when(setOps.members(FcmNotificationService.USER_TOKENS_KEY_PREFIX + "target-1"))
                .thenReturn(Set.of("tok-shared"));
        when(valueOps.get(FcmNotificationService.TOKEN_OWNER_KEY_PREFIX + "tok-shared")).thenReturn("someone-else");

        service.onRoomMemberRemoved(new RoomMemberRemovedEvent(
                "room-9", "target-1", RoomMemberRemovedEvent.Reason.BANNED));

        verify(messaging, never()).unsubscribeFromTopicAsync(anyList(), anyString());
        verify(setOps, never()).remove(eq(FcmNotificationService.ROOMS_KEY_PREFIX + "tok-shared"), any());
    }

    @Test
    void removal_userWithoutTokens_isNoop() {
        enableFirebase();
        when(setOps.members(FcmNotificationService.USER_TOKENS_KEY_PREFIX + "target-1")).thenReturn(Set.of());

        service.onRoomMemberRemoved(new RoomMemberRemovedEvent(
                "room-9", "target-1", RoomMemberRemovedEvent.Reason.LEFT));

        verifyNoInteractions(messaging);
    }

    @Test
    void removalListener_runsAfterCommitOnFcmExecutor() throws Exception {
        var method = FcmNotificationService.class
                .getMethod("onRoomMemberRemoved", RoomMemberRemovedEvent.class);
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo(AsyncConfig.FCM_EXECUTOR);
    }
}
