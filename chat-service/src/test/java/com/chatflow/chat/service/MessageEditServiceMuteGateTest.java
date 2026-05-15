package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageEditService — mute gate (Phase 2A)")
class MessageEditServiceMuteGateTest {

    private static final String MESSAGE_ID = "msg-1";
    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private MessageEncryptor messageEncryptor;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private com.chatflow.chat.repository.MessageEditHistoryRepository editHistoryRepository;

    @InjectMocks private MessageEditService service;

    private ChatMessageEntity msg;

    @BeforeEach
    void setUp() {
        msg = new ChatMessageEntity();
        msg.setMessageId(MESSAGE_ID);
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(USER_ID);
        msg.setUsername("alice");
        msg.setContent("hello");
        msg.setTimestamp(LocalDateTime.now());
    }

    private RoomMemberEntity member(LocalDateTime mutedUntil) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_ID)
                .username("alice")
                .role(RoomRole.MEMBER)
                .mutedUntil(mutedUntil)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("mute 활성 상태면 editMessage가 false 반환 + 저장 안 됨")
    void editMessage_muted_isRejected() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(member(LocalDateTime.now().plusMinutes(10))));

        boolean result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertFalse(result);
        verify(chatMessageRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("mute 만료된 사용자는 editMessage 정상 동작")
    void editMessage_muteExpired_proceeds() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(member(LocalDateTime.now().minusMinutes(1))));
        when(messageEncryptor.isEnabled()).thenReturn(false);

        boolean result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertTrue(result);
        verify(chatMessageRepository).save(msg);
    }

    @Test
    @DisplayName("mute 정보가 없는(null) 사용자는 정상 편집 가능")
    void editMessage_noMuteInfo_proceeds() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(member(null)));
        when(messageEncryptor.isEnabled()).thenReturn(false);

        boolean result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertTrue(result);
        verify(chatMessageRepository).save(msg);
    }

    @Test
    @DisplayName("멤버 레코드가 없으면 (방을 나간 사용자 등) mute 게이트 skip")
    void editMessage_memberRecordMissing_proceeds() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(messageEncryptor.isEnabled()).thenReturn(false);

        boolean result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertTrue(result);
    }
}
