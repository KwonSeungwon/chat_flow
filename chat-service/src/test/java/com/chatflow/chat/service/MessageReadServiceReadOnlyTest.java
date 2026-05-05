package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test: decryptEntity must not mutate the managed entity, otherwise
 * Hibernate dirty-checking would issue UPDATEs and could persist plaintext back.
 */
@ExtendWith(MockitoExtension.class)
class MessageReadServiceReadOnlyTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private MessageEncryptor messageEncryptor;

    private MessageReadService service;

    @BeforeEach
    void setUp() {
        service = new MessageReadService(chatMessageRepository, messageEncryptor);
    }

    private ChatMessageEntity entity(String id, String encryptedContent) {
        return ChatMessageEntity.builder()
                .messageId(id)
                .chatRoomId("room-1")
                .username("alice")
                .content(encryptedContent)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Test
    void getMessages_doesNotMutateOriginalEntity_whenEncryptionEnabled() {
        ChatMessageEntity original = entity("m-1", "ENC[abc]");
        Pageable pageable = PageRequest.of(0, 10);
        Page<ChatMessageEntity> page = new PageImpl<>(List.of(original), pageable, 1);

        when(chatMessageRepository.findByChatRoomIdOrderByTimestampDesc("room-1", pageable))
                .thenReturn(page);
        when(messageEncryptor.isEnabled()).thenReturn(true);
        when(messageEncryptor.decrypt("ENC[abc]")).thenReturn("hello");

        Page<ChatMessageEntity> result = service.getMessages("room-1", pageable);

        assertThat(result.getContent().get(0).getContent()).isEqualTo("hello");
        // The persistence-managed entity must remain encrypted — otherwise dirty
        // checking would write the plaintext back.
        assertThat(original.getContent()).isEqualTo("ENC[abc]");
    }

    @Test
    void getMessagesByCursor_doesNotMutateOriginalEntity_whenEncryptionEnabled() {
        ChatMessageEntity original = entity("m-1", "ENC[xyz]");
        when(chatMessageRepository.findLatestByChatRoomId(eq("room-1"), any()))
                .thenReturn(List.of(original));
        when(messageEncryptor.isEnabled()).thenReturn(true);
        when(messageEncryptor.decrypt("ENC[xyz]")).thenReturn("world");

        List<ChatMessageEntity> result = service.getMessagesByCursor("room-1", null, 50);

        assertThat(result.get(0).getContent()).isEqualTo("world");
        assertThat(original.getContent()).isEqualTo("ENC[xyz]");
    }

    @Test
    void getMessages_passesThroughUnchanged_whenEncryptionDisabled() {
        // Sanity: when the encryptor is disabled, the original page is returned as-is.
        ChatMessageEntity original = entity("m-1", "plain");
        Pageable pageable = PageRequest.of(0, 10);
        Page<ChatMessageEntity> page = new PageImpl<>(List.of(original), pageable, 1);

        when(chatMessageRepository.findByChatRoomIdOrderByTimestampDesc("room-1", pageable))
                .thenReturn(page);
        when(messageEncryptor.isEnabled()).thenReturn(false);

        Page<ChatMessageEntity> result = service.getMessages("room-1", pageable);

        assertThat(result.getContent().get(0).getContent()).isEqualTo("plain");
        assertThat(original.getContent()).isEqualTo("plain");
    }

    @Test
    void getMessages_skipsDecryption_whenContentIsNull() {
        // Sanity: null content short-circuits in decryptEntity (system messages may
        // have null content). Don't call decrypt on null.
        ChatMessageEntity original = entity("m-1", null);
        Pageable pageable = PageRequest.of(0, 10);
        Page<ChatMessageEntity> page = new PageImpl<>(List.of(original), pageable, 1);

        when(chatMessageRepository.findByChatRoomIdOrderByTimestampDesc("room-1", pageable))
                .thenReturn(page);
        when(messageEncryptor.isEnabled()).thenReturn(true);

        Page<ChatMessageEntity> result = service.getMessages("room-1", pageable);

        assertThat(result.getContent().get(0).getContent()).isNull();
        verify(messageEncryptor, never()).decrypt(any());
    }

    @Test
    void decryptedCopy_preservesIsNewFlag_matchingSourceEntity() {
        // Regression for I-1: managed entities have isNew=false after @PostLoad.
        // toBuilder() must preserve that on the detached copy so JSON serialization
        // ("new": false) stays consistent with non-encrypted reads.
        ChatMessageEntity loaded = entity("m-1", "ENC[abc]");
        loaded.setNew(false); // simulate @PostLoad effect
        Pageable pageable = PageRequest.of(0, 10);
        Page<ChatMessageEntity> page = new PageImpl<>(List.of(loaded), pageable, 1);

        when(chatMessageRepository.findByChatRoomIdOrderByTimestampDesc("room-1", pageable))
                .thenReturn(page);
        when(messageEncryptor.isEnabled()).thenReturn(true);
        when(messageEncryptor.decrypt("ENC[abc]")).thenReturn("hello");

        Page<ChatMessageEntity> result = service.getMessages("room-1", pageable);

        assertThat(result.getContent().get(0).isNew()).isFalse();
    }
}
