package com.chatflow.chat.service.message;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatMessageMapper chatMessageMapper;

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

        // Stub mapper for tests where edit succeeds and triggers outbox event
        lenient().when(chatMessageMapper.toDto(msg)).thenReturn(
                ChatMessage.builder()
                        .messageId(MESSAGE_ID)
                        .chatRoomId(ROOM_ID)
                        .userId(USER_ID)
                        .username("alice")
                        .content("hello")
                        .build());
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
    @DisplayName("멤버 레코드가 없으면 (방을 나간 사용자 등) mute 게이트 skip")
    void editMessage_memberRecordMissing_proceeds() {
        when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(messageEncryptor.isEnabled()).thenReturn(false);

        Result<Void, ChatErrorCode> result = service.editMessage(MESSAGE_ID, USER_ID, "edited content");

        assertThat(result.isSuccess()).isTrue();
    }
}
