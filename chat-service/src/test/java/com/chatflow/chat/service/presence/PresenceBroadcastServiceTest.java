package com.chatflow.chat.service.presence;

import com.chatflow.chat.event.MessagePersistedEvent;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * JOIN/LEAVE는 DB 행 없이 MessagePersistedEvent만 발행해 커밋 후 리스너가 방에
 * 브로드캐스트한다. 실제 ChatPersistenceService를 써서 이벤트가 정확히 한 번 나가고
 * 메시지·멘션 저장소는 건드리지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PresenceBroadcastServiceTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MessageMentionRepository messageMentionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageEncryptor messageEncryptor;
    @Mock ChatMessageMapper chatMessageMapper;

    private PresenceBroadcastService broadcaster;

    @BeforeEach
    void setUp() {
        ChatPersistenceService persistence = new ChatPersistenceService(
                chatMessageRepository, messageMentionRepository, eventPublisher,
                messageEncryptor, chatMessageMapper);
        broadcaster = new PresenceBroadcastService(messagingTemplate, persistence);
    }

    private MessagePersistedEvent capturePublished() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(MessagePersistedEvent.class);
        return (MessagePersistedEvent) captor.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastJoin_publishesMessagePersistedEvent() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUsername("alice");

        broadcaster.broadcastJoin(msg, 3);

        assertThat(msg.getType()).isEqualTo(ChatMessage.MessageType.JOIN);
        assertThat(msg.getContent()).isEqualTo("alice님이 입장하셨습니다.");
        assertThat(msg.getMessageId()).isNotBlank();

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/room-1/presence"), body.capture());
        assertThat(body.getValue())
                .containsEntry("type", "JOIN")
                .containsEntry("username", "alice")
                .containsEntry("participantCount", 3);

        assertThat(capturePublished().getMessage()).isSameAs(msg);
        verifyNoInteractions(chatMessageRepository, messageMentionRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastLeave_sends_leave_topic() {
        broadcaster.broadcastLeave("room-1", "alice", 2);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/room-1/presence"), body.capture());
        assertThat(body.getValue())
                .containsEntry("type", "LEAVE")
                .containsEntry("username", "alice")
                .containsEntry("participantCount", 2);
    }

    /** LEAVE 메시지 이벤트는 퇴장 경로의 persistLeaveEvent가 발행한다. */
    @Test
    void broadcastLeave_publishesMessagePersistedEvent() {
        broadcaster.persistLeaveEvent("room-1", "alice");

        ChatMessage published = capturePublished().getMessage();
        assertThat(published.getType()).isEqualTo(ChatMessage.MessageType.LEAVE);
        assertThat(published.getChatRoomId()).isEqualTo("room-1");
        assertThat(published.getContent()).isEqualTo("alice님이 퇴장하셨습니다.");
        verifyNoInteractions(chatMessageRepository, messageMentionRepository);
    }
}
