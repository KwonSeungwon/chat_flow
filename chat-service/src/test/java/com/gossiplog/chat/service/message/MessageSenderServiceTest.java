package com.gossiplog.chat.service.message;

import com.gossiplog.chat.entity.ChatMessageEntity;
import com.gossiplog.chat.repository.ChatMessageRepository;
import com.gossiplog.chat.repository.RoomMemberRepository;
import com.gossiplog.chat.service.notification.FcmNotificationService;
import com.gossiplog.chat.service.outbox.ChatPersistenceService;
import com.gossiplog.chat.service.room.ChatRoomService;
import com.gossiplog.common.dto.BaseMessage.MessageType;
import com.gossiplog.common.dto.ChatMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for MessageSenderService.send(): reply scoping and server-owned flags. */
class MessageSenderServiceTest {

    // ── send(): reply scoping and server-owned flags ───────────────

    private static final String ROOM_ID = "room-1";
    private static final String OTHER_ROOM_ID = "room-secret";
    private static final String USER_ID = "user-1";

    private final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    private final ChatRoomService chatRoomService = mock(ChatRoomService.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final RoomMemberRepository roomMemberRepository = mock(RoomMemberRepository.class);
    private final MessageSenderService wired = new MessageSenderService(
            persistence, chatRoomService, mock(FcmNotificationService.class),
            chatMessageRepository, roomMemberRepository, mock(SimpMessagingTemplate.class),
            new SimpleMeterRegistry());

    private ChatMessage userChat(String content) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(USER_ID);
        msg.setUsername("alice");
        msg.setType(MessageType.CHAT);
        msg.setContent(content);
        return msg;
    }

    private ChatMessage persisted() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(persistence).persistMessageAndPublish(
                captor.capture(), anyList());
        return captor.getValue();
    }

    @Test
    void send_replyToMessageInOtherRoom_parentIgnored_noPreview() {
        when(chatMessageRepository.findById("secret-msg")).thenReturn(Optional.of(
                ChatMessageEntity.builder().messageId("secret-msg").chatRoomId(OTHER_ROOM_ID)
                        .username("ceo").content("the merger closes friday").build()));
        ChatMessage message = userChat("replying");
        message.setParentMessageId("secret-msg");

        wired.send(message);

        ChatMessage sent = persisted();
        assertThat(sent.getParentMessageId()).isNull();
        assertThat(sent.getParentMessagePreview()).isNull();
    }

    @Test
    void send_replyToReplyWhoseRootIsInOtherRoom_parentIgnored_noPreview() {
        when(chatMessageRepository.findById("reply-1")).thenReturn(Optional.of(
                ChatMessageEntity.builder().messageId("reply-1").chatRoomId(ROOM_ID)
                        .parentMessageId("secret-root").username("bob").content("x").build()));
        when(chatMessageRepository.findById("secret-root")).thenReturn(Optional.of(
                ChatMessageEntity.builder().messageId("secret-root").chatRoomId(OTHER_ROOM_ID)
                        .username("ceo").content("secret").build()));
        ChatMessage message = userChat("replying");
        message.setParentMessageId("reply-1");

        wired.send(message);

        ChatMessage sent = persisted();
        assertThat(sent.getParentMessageId()).isNull();
        assertThat(sent.getParentMessagePreview()).isNull();
    }

    @Test
    void send_replyToMessageInSameRoom_previewBuilt() {
        when(chatMessageRepository.findById("p-1")).thenReturn(Optional.of(
                ChatMessageEntity.builder().messageId("p-1").chatRoomId(ROOM_ID)
                        .username("bob").content("original").build()));
        ChatMessage message = userChat("replying");
        message.setParentMessageId("p-1");

        wired.send(message);

        ChatMessage sent = persisted();
        assertThat(sent.getParentMessageId()).isEqualTo("p-1");
        assertThat(sent.getParentMessagePreview()).isEqualTo("bob: original");
    }

    @Test
    void send_aiGeneratedFlag_forcedFalse() {
        ChatMessage message = userChat("pretending to be the AI");
        message.setAiGenerated(true);

        wired.send(message);

        assertThat(persisted().isAiGenerated()).isFalse();
    }
}
