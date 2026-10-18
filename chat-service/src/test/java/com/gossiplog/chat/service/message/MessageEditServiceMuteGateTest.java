package com.gossiplog.chat.service.message;

import com.gossiplog.chat.entity.ChatMessageEntity;
import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.entity.RoomRole;
import com.gossiplog.chat.repository.ChatMessageRepository;
import com.gossiplog.chat.repository.MessageMentionRepository;
import com.gossiplog.chat.repository.RoomMemberRepository;
import com.gossiplog.chat.result.ChatErrorCode;
import com.gossiplog.chat.result.Result;
import com.gossiplog.common.util.MessageEncryptor;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private MessageMentionRepository messageMentionRepository;
    @Mock private MessageEncryptor messageEncryptor;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private com.gossiplog.chat.repository.MessageEditHistoryRepository editHistoryRepository;

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
    @DisplayName("mute 활성 상태면 editMessage가 MUTED 반환 + 저장 안 됨")
    void editMessage_muted_isRejected() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(member(LocalDateTime.now().plusMinutes(10))));

        Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.error()).isEqualTo(ChatErrorCode.MUTED);
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

        Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertThat(result.isSuccess()).isTrue();
        verify(chatMessageRepository).save(msg);
    }

    @Test
    @DisplayName("mute 정보가 없는(null) 사용자는 정상 편집 가능")
    void editMessage_noMuteInfo_proceeds() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(member(null)));
        when(messageEncryptor.isEnabled()).thenReturn(false);

        Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertThat(result.isSuccess()).isTrue();
        verify(chatMessageRepository).save(msg);
    }

    @Test
    @DisplayName("멤버 레코드가 없으면 (강퇴·밴·나간 사용자) 편집 거부")
    void editMessage_memberRecordMissing_isForbidden() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());

        Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.error()).isEqualTo(ChatErrorCode.FORBIDDEN);
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("멤버가 아니면 (강퇴·밴·나간 사용자) 자기 메시지도 삭제 거부")
    void deleteMessage_nonMember_isForbidden() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);

        Result<Void, ChatErrorCode> result = service.deleteMessage(MESSAGE_ID, USER_ID);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.error()).isEqualTo(ChatErrorCode.FORBIDDEN);
        verify(chatMessageRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("현재 멤버는 자기 메시지를 삭제할 수 있다")
    void deleteMessage_member_proceeds() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);

        Result<Void, ChatErrorCode> result = service.deleteMessage(MESSAGE_ID, USER_ID);

        assertThat(result.isSuccess()).isTrue();
        verify(chatMessageRepository).save(msg);
    }
}
