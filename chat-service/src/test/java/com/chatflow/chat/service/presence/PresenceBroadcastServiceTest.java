package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresenceBroadcastServiceTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock ChatPersistenceService chatPersistenceService;

    private PresenceBroadcastService broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new PresenceBroadcastService(messagingTemplate, chatPersistenceService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastJoin_sends_presence_topic_and_outbox_event() {
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

        verify(chatPersistenceService).saveOutboxEventAndPublish(
                eq(msg), eq(KafkaTopics.CHAT_MESSAGES), eq("USER_JOINED"));
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

    @Test
    void persistLeaveEvent_writes_outbox_event() {
        broadcaster.persistLeaveEvent("room-1", "alice");
        verify(chatPersistenceService).saveOutboxEventAndPublish(
                any(ChatMessage.class), eq(KafkaTopics.CHAT_MESSAGES), eq("USER_LEFT"));
    }
}
