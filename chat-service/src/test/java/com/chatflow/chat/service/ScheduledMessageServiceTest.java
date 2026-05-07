package com.chatflow.chat.service;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.chatflow.chat.repository.ScheduledMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledMessageServiceTest {

    @Mock private ScheduledMessageRepository repository;
    @Mock private MessageSenderService messageSenderService;
    @Mock private RoomPermissionService roomPermissionService;

    private ScheduledMessageService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledMessageService(repository, messageSenderService, roomPermissionService);
    }

    private ScheduledMessageEntity sample(Long id, ScheduledMessageStatus status, LocalDateTime when) {
        return ScheduledMessageEntity.builder()
                .id(id)
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("hello future")
                .scheduledAt(when)
                .status(status)
                .build();
    }

    @Test
    void schedule_persistsPendingRowAndReturnsIt() {
        when(repository.save(any())).thenAnswer(inv -> {
            ScheduledMessageEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        ScheduledMessageEntity saved = service.schedule(
                "room-1", "user-1", "alice", "hi later",
                LocalDateTime.now().plusMinutes(30));

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo(ScheduledMessageStatus.PENDING);
        assertThat(saved.getContent()).isEqualTo("hi later");
        assertThat(saved.getChatRoomId()).isEqualTo("room-1");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getUsername()).isEqualTo("alice");
    }

    @Test
    void schedule_rejectsPastTimes() {
        assertThatThrownBy(() -> service.schedule(
                "room-1", "user-1", "alice", "x",
                LocalDateTime.now().minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in the future");
        verifyNoInteractions(repository);
    }

    @Test
    void cancel_marksRowCanceled_whenOwnerMatchesAndPending() {
        when(repository.findByIdAndUserId(7L, "user-1"))
                .thenReturn(Optional.of(sample(7L, ScheduledMessageStatus.PENDING,
                        LocalDateTime.now().plusHours(1))));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledMessageEntity result = service.cancel(7L, "user-1");

        assertThat(result.getStatus()).isEqualTo(ScheduledMessageStatus.CANCELED);
        verify(repository).save(any(ScheduledMessageEntity.class));
    }

    @Test
    void cancel_throws_whenNotOwner() {
        when(repository.findByIdAndUserId(7L, "intruder")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(7L, "intruder"))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void cancel_isNoOp_whenAlreadyTerminal() {
        when(repository.findByIdAndUserId(7L, "user-1"))
                .thenReturn(Optional.of(sample(7L, ScheduledMessageStatus.SENT,
                        LocalDateTime.now().minusMinutes(10))));

        ScheduledMessageEntity result = service.cancel(7L, "user-1");

        assertThat(result.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
        verify(repository, never()).save(any());
    }

    @Test
    void listMine_delegatesToRepository() {
        var rows = List.of(
                sample(1L, ScheduledMessageStatus.PENDING, LocalDateTime.now().plusMinutes(5)),
                sample(2L, ScheduledMessageStatus.PENDING, LocalDateTime.now().plusMinutes(10)));
        when(repository.findByUserIdAndStatusOrderByScheduledAtDesc("user-1", ScheduledMessageStatus.PENDING))
                .thenReturn(rows);

        List<ScheduledMessageEntity> result = service.listMine("user-1");

        assertThat(result).hasSize(2);
    }

    @Test
    void deliverDue_skipsEmptyBatch() {
        when(repository.findDueForSending(any())).thenReturn(List.of());
        service.deliverDue();
        verify(messageSenderService, never()).send(any());
        verify(repository, never()).save(any());
    }

    @Test
    void deliverDue_sendsAndMarksSent() {
        ScheduledMessageEntity row = sample(11L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(row));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Mirror MessageSenderService.send()'s real contract: it assigns
        // messageId + timestamp on the ChatMessage it receives. The service
        // under test relies on this — capturing messageId AFTER send() returns.
        doAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setMessageId("fake-server-uuid");
            m.setTimestamp(java.time.LocalDateTime.now());
            return null;
        }).when(messageSenderService).send(any());

        service.deliverDue();

        ArgumentCaptor<ChatMessage> sent = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageSenderService).send(sent.capture());
        ChatMessage actual = sent.getValue();
        assertThat(actual.getContent()).isEqualTo("hello future");
        assertThat(actual.getChatRoomId()).isEqualTo("room-1");
        assertThat(actual.getUserId()).isEqualTo("user-1");
        assertThat(actual.getUsername()).isEqualTo("alice");
        assertThat(actual.getMessageId()).isNotNull();
        assertThat(actual.getType()).isEqualTo(ChatMessage.MessageType.CHAT);

        assertThat(row.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
        assertThat(row.getSentMessageId()).isEqualTo(actual.getMessageId());
    }

    @Test
    void deliverDue_marksFailedOnSendError_andContinuesNextRow() {
        ScheduledMessageEntity bad = sample(1L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(2));
        ScheduledMessageEntity good = sample(2L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(bad, good));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // First send throws, second succeeds.
        doThrow(new RuntimeException("kafka down"))
                .doNothing()
                .when(messageSenderService).send(any());

        service.deliverDue();

        assertThat(bad.getStatus()).isEqualTo(ScheduledMessageStatus.FAILED);
        assertThat(bad.getErrorMessage()).contains("kafka down");
        assertThat(good.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
    }

    @Test
    void deliverDue_optimisticLockLost_isBenign_noFailedMark() {
        // Race condition: another replica already updated this row.
        // Our save() throws ObjectOptimisticLockingFailureException.
        // Expected: the row is NOT marked FAILED; we just log and move on.
        ScheduledMessageEntity row = sample(1L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(row));
        when(repository.save(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        ScheduledMessageEntity.class, 1L));

        service.deliverDue();

        // send() WAS called (we tried to deliver before save failed)
        verify(messageSenderService).send(any());
        // The row's status was set to SENT in memory before save threw —
        // but the row's persisted state is unchanged because save threw.
        // The key invariant: we do NOT explicitly flip to FAILED.
        // Verify by checking the count of save() invocations: exactly 1
        // (the SENT save that threw). No second save() call (which would
        // be the FAILED mark).
        verify(repository, times(1)).save(any());
    }
}
