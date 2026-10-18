package com.gossiplog.chat.service.notification;

import com.gossiplog.chat.exception.QuotaExceededException;
import com.gossiplog.chat.service.RoomPermissionService;

import com.gossiplog.chat.entity.ScheduledMessageEntity;
import com.gossiplog.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.gossiplog.chat.repository.ScheduledMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledMessageServiceTest {

    @Mock private ScheduledMessageRepository repository;
    @Mock private ScheduledMessageDeliverer deliverer;
    @Mock private RoomPermissionService roomPermissionService;

    private ScheduledMessageService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledMessageService(repository, deliverer, roomPermissionService);
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
                Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo(ScheduledMessageStatus.PENDING);
        assertThat(saved.getContent()).isEqualTo("hi later");
        assertThat(saved.getChatRoomId()).isEqualTo("room-1");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getUsername()).isEqualTo("alice");
    }

    @Test
    void schedule_offsetInput_storedAsUtc() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // A KST user picks 09:00 local, one day out. The column holds UTC wall time.
        OffsetDateTime kst = OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);

        ScheduledMessageEntity saved = service.schedule(
                "room-1", "user-1", "alice", "morning", kst.toInstant());

        assertThat(saved.getScheduledAt())
                .isEqualTo(kst.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        assertThat(saved.getScheduledAt().getHour()).isZero();
    }

    @Test
    void schedule_rejectsPastTimes() {
        assertThatThrownBy(() -> service.schedule(
                "room-1", "user-1", "alice", "x",
                Instant.now().minus(1, ChronoUnit.MINUTES)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in the future");
        verifyNoInteractions(repository);
    }

    @Test
    void schedule_throwsQuotaExceeded_whenPendingCapReached() {
        when(repository.countByUserIdAndStatus("user-1", ScheduledMessageStatus.PENDING))
                .thenReturn(100L);

        assertThatThrownBy(() -> service.schedule(
                "room-1", "user-1", "alice", "one more",
                Instant.now().plus(30, ChronoUnit.MINUTES)))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("scheduled message limit reached");
        verify(repository, never()).save(any());
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
        verifyNoInteractions(deliverer);
    }

    @Test
    void deliverDue_oneRowFails_othersStillDelivered() {
        ScheduledMessageEntity bad = sample(1L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(2));
        ScheduledMessageEntity good = sample(2L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(bad, good));
        // Row 1's REQUIRES_NEW transaction blows up (e.g. ChatPersistenceService
        // marked it rollback-only). Row 2 runs in its own transaction regardless.
        when(deliverer.deliver(1L)).thenThrow(new RuntimeException("kafka down"));
        when(deliverer.deliver(2L)).thenReturn(ScheduledMessageDeliverer.Outcome.SENT);

        service.deliverDue();

        verify(deliverer).deliver(2L);
        // The failed row is terminally marked in a separate transaction, so it is
        // not picked up again every 30s.
        verify(deliverer).markFailed(eq(1L), contains("kafka down"));
        verify(deliverer, never()).markFailed(eq(2L), anyString());
    }

    @Test
    void deliverDue_markFailedItselfThrowing_doesNotAbortBatch() {
        ScheduledMessageEntity bad = sample(1L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(2));
        ScheduledMessageEntity good = sample(2L, ScheduledMessageStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findDueForSending(any())).thenReturn(List.of(bad, good));
        when(deliverer.deliver(1L)).thenThrow(new RuntimeException("boom"));
        doThrow(new RuntimeException("db down")).when(deliverer).markFailed(eq(1L), anyString());

        service.deliverDue();

        verify(deliverer).deliver(2L);
    }

    @Test
    void deliverDue_queriesDueRowsAgainstUtcClock() {
        when(repository.findDueForSending(any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        service.deliverDue();

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findDueForSending(now.capture());
        assertThat(now.getValue()).isBetween(before, LocalDateTime.now(ZoneOffset.UTC));
    }
}
