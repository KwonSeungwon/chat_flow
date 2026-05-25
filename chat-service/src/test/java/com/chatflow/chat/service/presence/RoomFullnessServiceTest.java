package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomFullnessServiceTest {

    @Mock ParticipantService participantService;
    @Mock ChatRoomService chatRoomService;
    @Mock RoomMemberRepository roomMemberRepository;
    @Mock SimpMessagingTemplate messagingTemplate;

    private RoomFullnessService fullness;

    @BeforeEach
    void setUp() {
        fullness = new RoomFullnessService(
                participantService, chatRoomService, roomMemberRepository, messagingTemplate);
    }

    @Test
    void room_not_full_returns_false() {
        when(participantService.isRoomFull("room-1")).thenReturn(false);
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        assertThat(fullness.handleIfFull(msg, "user-1", false)).isFalse();
    }

    @Test
    void already_joined_returns_false_even_if_full() {
        when(participantService.isRoomFull("room-1")).thenReturn(true);
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        assertThat(fullness.handleIfFull(msg, "user-1", true)).isFalse();
    }

    @Test
    void DM_full_rejects_non_member() {
        ChatRoom dm = ChatRoom.builder().id("dm-1").name("dm").roomType(RoomType.DIRECT).build();
        when(participantService.isRoomFull("dm-1")).thenReturn(true);
        when(chatRoomService.getRoom("dm-1")).thenReturn(Optional.of(dm));
        when(roomMemberRepository.existsByRoomIdAndUserId("dm-1", "user-1")).thenReturn(false);

        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("dm-1");
        msg.setUsername("alice");

        assertThat(fullness.handleIfFull(msg, "user-1", false)).isTrue();
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/dm-1/errors"), any(java.util.Map.class));
    }

    @Test
    void DM_full_allows_existing_member() {
        ChatRoom dm = ChatRoom.builder().id("dm-1").name("dm").roomType(RoomType.DIRECT).build();
        when(participantService.isRoomFull("dm-1")).thenReturn(true);
        when(chatRoomService.getRoom("dm-1")).thenReturn(Optional.of(dm));
        when(roomMemberRepository.existsByRoomIdAndUserId("dm-1", "user-1")).thenReturn(true);

        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("dm-1");
        msg.setUsername("alice");

        assertThat(fullness.handleIfFull(msg, "user-1", false)).isFalse();
    }

    @Test
    void general_room_full_mutates_chatRoomId_and_broadcasts_redirect() {
        ChatRoom original = ChatRoom.builder().id("room-1").name("일반-1").roomType(RoomType.GENERAL).build();
        ChatRoom newRoom = ChatRoom.builder().id("room-2").name("일반-2").roomType(RoomType.GENERAL).build();
        when(participantService.isRoomFull("room-1")).thenReturn(true);
        when(chatRoomService.getRoom("room-1")).thenReturn(Optional.of(original));
        when(participantService.findOrCreateAvailableRoom("일반")).thenReturn(newRoom);

        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUsername("alice");

        assertThat(fullness.handleIfFull(msg, "user-1", false)).isFalse();
        assertThat(msg.getChatRoomId()).isEqualTo("room-2");
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/room-1/errors"), any(java.util.Map.class));
    }
}
