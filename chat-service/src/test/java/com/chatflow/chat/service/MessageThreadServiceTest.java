package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageThreadServiceTest {

    @Mock private ChatMessageRepository repo;

    private MessageThreadService service;

    @BeforeEach
    void setUp() {
        service = new MessageThreadService(repo);
    }

    @Test
    void findReplies_returns_dto_list_for_parent() {
        ChatMessageEntity reply = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT.name())
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .deleted(false)
            .build();
        when(repo.findByParentMessageIdOrderByTimestampAsc("p1"))
            .thenReturn(List.of(reply));

        List<ChatMessage> replies = service.findReplies("p1");

        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).getMessageId()).isEqualTo("r1");
        assertThat(replies.get(0).getParentMessageId()).isEqualTo("p1");
        assertThat(replies.get(0).getContent()).isEqualTo("got it");
    }

    @Test
    void findReplies_filters_deleted() {
        ChatMessageEntity deleted = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT.name())
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .deleted(true)
            .build();
        when(repo.findByParentMessageIdOrderByTimestampAsc("p1"))
            .thenReturn(List.of(deleted));

        List<ChatMessage> replies = service.findReplies("p1");

        assertThat(replies).isEmpty();
    }

    @Test
    void findReplies_empty_when_no_replies() {
        when(repo.findByParentMessageIdOrderByTimestampAsc("p1"))
            .thenReturn(List.of());

        assertThat(service.findReplies("p1")).isEmpty();
    }
}
