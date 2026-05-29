package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.room.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParticipantRegistryServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    @Mock SetOperations<String, String> setOperations;
    @Mock RoomMemberRepository roomMemberRepository;
    @Mock ParticipantService participantService;

    private ParticipantRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = new ParticipantRegistryService(redisTemplate, roomMemberRepository, participantService);
    }

    @Test
    void getRoomParticipantUserIds_extracts_first_segment_and_dedupes() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1"))
                .thenReturn(Set.of("u1:s1:alice", "u1:s2:alice", "u2:s3:bob"));

        Set<String> result = registry.getRoomParticipantUserIds("room-1");

        assertThat(result).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void getRoomParticipantUserIds_returns_empty_when_no_members() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1")).thenReturn(null);

        assertThat(registry.getRoomParticipantUserIds("room-1")).isEmpty();
    }

    @Test
    void register_writes_to_redis_and_backfills_room_member() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1")).thenReturn(false);

        registry.register(msg, "session-1");

        verify(setOperations).add("chatflow:room:participants:room-1", "user-1:session-1:alice");
        verify(redisTemplate).expire(eq("chatflow:room:participants:room-1"), anyLong(), any());
        verify(roomMemberRepository).save(any(RoomMemberEntity.class));
        verify(participantService).setParticipantCount(eq("room-1"), anyInt());
    }

    @Test
    void register_swallows_DataIntegrityViolation_on_concurrent_insert() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1")).thenReturn(false);
        when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        registry.register(msg, "session-1");  // must not throw
    }

    @Test
    void register_propagates_non_DataIntegrityViolation_exceptions() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1")).thenReturn(false);
        when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                .thenThrow(new IllegalStateException("DB down"));

        assertThatThrownBy(() -> registry.register(msg, "session-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB down");
    }

    @Test
    void register_skips_save_when_user_is_already_a_room_member() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1")).thenReturn(true);

        registry.register(msg, "session-1");

        // Redis SET write still happened
        verify(setOperations).add("chatflow:room:participants:room-1", "user-1:session-1:alice");
        // But save() never attempted
        verify(roomMemberRepository, never()).save(any(RoomMemberEntity.class));
    }

    @Test
    void removeSession_removes_specific_session_entry() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1"))
                .thenReturn(Set.of("u1:s1:alice", "u1:s2:alice"));

        registry.removeSession("room-1", "alice", "s1");

        verify(setOperations).remove("chatflow:room:participants:room-1", "u1:s1:alice");
        verify(setOperations, never()).remove(anyString(), eq("u1:s2:alice"));
        // Exactly one remove() call total — no spurious deletes from filter drift
        verify(setOperations, times(1)).remove(anyString(), anyString());
    }

    @Test
    void removeSession_removes_all_user_entries_when_sessionId_null() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1"))
                .thenReturn(Set.of("u1:s1:alice", "u1:s2:alice", "u2:s9:bob"));

        registry.removeSession("room-1", "alice", null);

        // both alice entries removed (suffix match on ":alice")
        verify(setOperations).remove("chatflow:room:participants:room-1", "u1:s1:alice");
        verify(setOperations).remove("chatflow:room:participants:room-1", "u1:s2:alice");
        // bob untouched
        verify(setOperations, never()).remove(anyString(), eq("u2:s9:bob"));
    }
}
