package com.chatflow.chat.service.read;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReadServiceTest {

    @Mock private ChatMessageRepository repo;
    @Mock private MessageEncryptor encryptor;

    private MessageReadService service;

    @BeforeEach
    void setUp() {
        service = new MessageReadService(repo, encryptor);
    }

    private ChatMessageEntity msg(String id, String content) {
        return ChatMessageEntity.builder().messageId(id).chatRoomId("r").username("a")
                .content(content).timestamp(LocalDateTime.of(2026, 6, 7, 9, 0)).type("CHAT").build();
    }

    @Test
    void getMessagesByCursor_null_before_fetches_latest() {
        when(encryptor.isEnabled()).thenReturn(false);
        when(repo.findLatestByChatRoomId(eq("r"), any(Pageable.class)))
                .thenReturn(List.of(msg("m1", "hi")));
        List<ChatMessageEntity> out = service.getMessagesByCursor("r", null, 50);
        assertThat(out).hasSize(1);
        verify(repo).findLatestByChatRoomId(eq("r"), any(Pageable.class));
        verify(repo, never()).findByChatRoomIdBeforeCursor(any(), any(), any());
    }

    @Test
    void getMessagesByCursor_with_before_uses_cursor_query() {
        when(encryptor.isEnabled()).thenReturn(false);
        LocalDateTime before = LocalDateTime.of(2026, 6, 7, 8, 0);
        when(repo.findByChatRoomIdBeforeCursor(eq("r"), eq(before), any(Pageable.class)))
                .thenReturn(List.of(msg("m0", "older")));
        List<ChatMessageEntity> out = service.getMessagesByCursor("r", before, 50);
        assertThat(out.get(0).getMessageId()).isEqualTo("m0");
        verify(repo).findByChatRoomIdBeforeCursor(eq("r"), eq(before), any(Pageable.class));
    }

    @Test
    void getMessagesByCursor_decrypts_content_when_enabled() {
        when(encryptor.isEnabled()).thenReturn(true);
        when(encryptor.decrypt("cipher")).thenReturn("plain");
        when(repo.findLatestByChatRoomId(eq("r"), any(Pageable.class)))
                .thenReturn(List.of(msg("m1", "cipher")));
        List<ChatMessageEntity> out = service.getMessagesByCursor("r", null, 50);
        assertThat(out.get(0).getContent()).isEqualTo("plain");
    }
}
