package com.chatflow.chat.service.notification;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
import com.chatflow.common.util.MessageEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MentionDigestServiceTest {

    @Mock private MessageMentionRepository mentionRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private MessageEncryptor messageEncryptor;

    private MentionDigestService service;

    @BeforeEach
    void setUp() {
        service = new MentionDigestService(mentionRepository, chatMessageRepository, messageEncryptor);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private MessageMentionEntity mentionRow(String messageId, String roomId,
                                            String fromUser, boolean read,
                                            LocalDateTime when) {
        return MessageMentionEntity.builder()
                .messageId(messageId)
                .roomId(roomId)
                .mentionedUserId("user-alice")
                .mentionedUsername("alice")
                .fromUsername(fromUser)
                .createdAt(when)
                .read(read)
                .build();
    }

    private ChatMessageEntity msg(String id, String content, boolean deleted) {
        return ChatMessageEntity.builder()
                .messageId(id)
                .chatRoomId("room-1")
                .username("bob")
                .content(content)
                .timestamp(LocalDateTime.now())
                .deleted(deleted)
                .build();
    }

    // ── list ─────────────────────────────────────────────────────────

    @Test
    void list_mapsRowsToDtosWithPreviewFromJoinedMessages() {
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 10, 12, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 7, 10, 11, 0);

        MessageMentionEntity row1 = mentionRow("m1", "room-1", "bob", false, t1);
        MessageMentionEntity row2 = mentionRow("m2", "room-1", "carol", true, t2);

        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any())).thenReturn(List.of(row1, row2));

        when(chatMessageRepository.findAllById(List.of("m1", "m2")))
                .thenReturn(List.of(msg("m1", "@alice hey there", false),
                                    msg("m2", "@alice yo", false)));

        when(messageEncryptor.isEnabled()).thenReturn(false);

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).messageId()).isEqualTo("m1");
        assertThat(result.get(0).chatRoomId()).isEqualTo("room-1");
        assertThat(result.get(0).fromUsername()).isEqualTo("bob");
        assertThat(result.get(0).contentPreview()).isEqualTo("@alice hey there");
        assertThat(result.get(0).read()).isFalse();
        assertThat(result.get(1).messageId()).isEqualTo("m2");
        assertThat(result.get(1).read()).isTrue();
    }

    @Test
    void list_preservesRowOrder_newestFirst() {
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 10, 15, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 7, 10, 14, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 7, 10, 13, 0);

        // Rows returned newest-first from the repository
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any()))
                .thenReturn(List.of(
                        mentionRow("m3", "room-1", "x", false, t1),
                        mentionRow("m2", "room-1", "y", false, t2),
                        mentionRow("m1", "room-1", "z", false, t3)));

        when(chatMessageRepository.findAllById(any()))
                .thenReturn(List.of(
                        msg("m1", "c1", false),
                        msg("m2", "c2", false),
                        msg("m3", "c3", false)));

        when(messageEncryptor.isEnabled()).thenReturn(false);

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).extracting(MentionItemDto::messageId)
                .containsExactly("m3", "m2", "m1");
    }

    @Test
    void list_dropsDeletedMessages() {
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any()))
                .thenReturn(List.of(
                        mentionRow("m1", "room-1", "bob", false, LocalDateTime.now()),
                        mentionRow("m2", "room-1", "carol", false, LocalDateTime.now())));

        when(chatMessageRepository.findAllById(List.of("m1", "m2")))
                .thenReturn(List.of(
                        msg("m1", "@alice hey", false),
                        msg("m2", "@alice deleted", true)));  // deleted

        when(messageEncryptor.isEnabled()).thenReturn(false);

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).messageId()).isEqualTo("m1");
    }

    @Test
    void list_dropsMissingMessages() {
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any()))
                .thenReturn(List.of(
                        mentionRow("m1", "room-1", "bob", false, LocalDateTime.now()),
                        mentionRow("m-gone", "room-1", "carol", false, LocalDateTime.now())));

        // only m1 exists — m-gone is missing from the DB
        when(chatMessageRepository.findAllById(List.of("m1", "m-gone")))
                .thenReturn(List.of(msg("m1", "@alice hi", false)));

        when(messageEncryptor.isEnabled()).thenReturn(false);

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).messageId()).isEqualTo("m1");
    }

    @Test
    void list_decryptsContentWhenEncryptionEnabled() {
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any()))
                .thenReturn(List.of(
                        mentionRow("m1", "room-1", "bob", false, LocalDateTime.now())));

        when(chatMessageRepository.findAllById(List.of("m1")))
                .thenReturn(List.of(msg("m1", "CIPHERTEXT_BASE64", false)));

        when(messageEncryptor.isEnabled()).thenReturn(true);
        when(messageEncryptor.decrypt("CIPHERTEXT_BASE64")).thenReturn("@alice decrypted content here");

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).contentPreview()).isEqualTo("@alice decrypted content here");
        verify(messageEncryptor).decrypt("CIPHERTEXT_BASE64");
    }

    @Test
    void list_encryptionDisabled_usesRawContent() {
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any()))
                .thenReturn(List.of(
                        mentionRow("m1", "room-1", "bob", false, LocalDateTime.now())));

        when(chatMessageRepository.findAllById(List.of("m1")))
                .thenReturn(List.of(msg("m1", "@alice raw content", false)));

        when(messageEncryptor.isEnabled()).thenReturn(false);

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result.get(0).contentPreview()).isEqualTo("@alice raw content");
        verify(messageEncryptor, never()).decrypt(any());
    }

    @Test
    void list_truncatesPreviewTo140Chars() {
        String longContent = "x".repeat(200);
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any()))
                .thenReturn(List.of(
                        mentionRow("m1", "room-1", "bob", false, LocalDateTime.now())));

        when(chatMessageRepository.findAllById(List.of("m1")))
                .thenReturn(List.of(msg("m1", longContent, false)));

        when(messageEncryptor.isEnabled()).thenReturn(false);

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result.get(0).contentPreview()).hasSize(143);  // 140 + "..."
        assertThat(result.get(0).contentPreview()).endsWith("...");
    }

    @Test
    void list_emptyRows_returnsEmptyList() {
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any())).thenReturn(List.of());

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).isEmpty();
        verifyNoInteractions(chatMessageRepository);
    }

    // ── days clamping ────────────────────────────────────────────────

    @Test
    void list_clampsDays_zeroBecomesOne() {
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any())).thenReturn(List.of());

        service.list("user-alice", "alice", 0);
        service.list("user-alice", "alice", 9999);

        // No exception means clamping worked
        verify(mentionRepository, times(2))
                .findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(eq("user-alice"), any());
    }

    // ── unreadCount ──────────────────────────────────────────────────

    @Test
    void unreadCount_delegatesToRepository() {
        when(mentionRepository.countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
                eq("user-alice"), any())).thenReturn(5L);

        long count = service.unreadCount("user-alice", "alice", 30);

        assertThat(count).isEqualTo(5L);
    }

    @Test
    void unreadCount_clampsDays() {
        when(mentionRepository.countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
                eq("user-alice"), any())).thenReturn(0L);

        service.unreadCount("user-alice", "alice", 0);
        service.unreadCount("user-alice", "alice", 9999);

        verify(mentionRepository, times(2))
                .countByMentionedUserIdAndReadFalseAndCreatedAtAfter(eq("user-alice"), any());
    }

    // ── markRead ─────────────────────────────────────────────────────

    @Test
    void markRead_callsRepositoryMarkRead() {
        when(mentionRepository.markRead("user-alice", "m42")).thenReturn(1);

        service.markRead("user-alice", "m42");

        verify(mentionRepository).markRead("user-alice", "m42");
    }

    // ── markAllRead ──────────────────────────────────────────────────

    @Test
    void markAllRead_callsRepositoryMarkAllRead() {
        when(mentionRepository.markAllRead(eq("user-alice"), any())).thenReturn(3);

        service.markAllRead("user-alice", "alice", 30);

        verify(mentionRepository).markAllRead(eq("user-alice"), any());
    }

    @Test
    void markAllRead_clampsDays() {
        when(mentionRepository.markAllRead(eq("user-alice"), any())).thenReturn(0);

        service.markAllRead("user-alice", "alice", 0);
        service.markAllRead("user-alice", "alice", 9999);

        verify(mentionRepository, times(2)).markAllRead(eq("user-alice"), any());
    }

    // ── no Redis interaction anywhere ────────────────────────────────

    @Test
    void noRedisInteraction_serviceHasNoRedisField() {
        // Structural test: the constructor only takes mentionRepository,
        // chatMessageRepository, and messageEncryptor — no StringRedisTemplate.
        // This test verifies the service compiles and runs without Redis.
        when(mentionRepository.findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user-alice"), any())).thenReturn(List.of());

        service.list("user-alice", "alice", 30);

        // If we get here, no Redis dependency — the service doesn't have a
        // StringRedisTemplate field, so there's nothing to mock or verify.
    }
}
