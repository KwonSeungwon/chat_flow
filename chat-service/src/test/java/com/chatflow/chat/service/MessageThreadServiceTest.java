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
import static org.mockito.Mockito.verify;
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
    void findReplies_delegates_to_db_filtered_query() {
        ChatMessageEntity reply = ChatMessageEntity.builder()
            .messageId("r1").chatRoomId("room-1").userId("u1").username("alice")
            .content("got it").type(ChatMessage.MessageType.CHAT.name())
            .parentMessageId("p1").timestamp(LocalDateTime.now())
            .reactions("{\"\\uD83D\\uDC4D\":[\"u9\"]}")
            .edited(true).editedAt(LocalDateTime.now())
            .pinned(true)
            .build();
        when(repo.findByParentMessageIdAndDeletedFalseOrderByTimestampAsc("p1"))
            .thenReturn(List.of(reply));

        List<ChatMessageEntity> replies = service.findReplies("p1");

        // The service is intentionally a thin pass-through to the deleted=false
        // repo method — verify both the result and the exact repo call.
        verify(repo).findByParentMessageIdAndDeletedFalseOrderByTimestampAsc("p1");
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).getMessageId()).isEqualTo("r1");
        assertThat(replies.get(0).getParentMessageId()).isEqualTo("p1");
        assertThat(replies.get(0).getContent()).isEqualTo("got it");
        // Critical fields the thread panel depends on — these are entity-only
        // and would have been dropped by an earlier DTO-mapping draft.
        assertThat(replies.get(0).getReactions()).isNotNull();
        assertThat(replies.get(0).isEdited()).isTrue();
        assertThat(replies.get(0).isPinned()).isTrue();
    }

    @Test
    void findReplies_empty_when_no_replies() {
        when(repo.findByParentMessageIdAndDeletedFalseOrderByTimestampAsc("p1"))
            .thenReturn(List.of());

        assertThat(service.findReplies("p1")).isEmpty();
    }
}
