package com.chatflow.chat.service;

import com.chatflow.chat.repository.MessageEditHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessageEditHistoryRetentionService covering the batch-loop
 * drain semantics and configurable retention-day cutoff.
 */
@ExtendWith(MockitoExtension.class)
class MessageEditHistoryRetentionServiceTest {

    @Mock private MessageEditHistoryRepository editHistoryRepository;

    private MessageEditHistoryRetentionService retentionService;

    @BeforeEach
    void setUp() throws Exception {
        retentionService = new MessageEditHistoryRetentionService(editHistoryRepository);
        // @Value is not processed in unit tests; set the default manually
        Field retentionDaysField = MessageEditHistoryRetentionService.class
                .getDeclaredField("retentionDays");
        retentionDaysField.setAccessible(true);
        retentionDaysField.set(retentionService, 90);
    }

    // ── Batch-loop semantics ────────────────────────────────────────

    @Test
    void purges_with_cutoff_minus_retentionDays_and_batches_until_drained() {
        // do/while loop continues while deleted == BATCH_SIZE (5000).
        // Returns: 5000, 5000, 2300 → 3 calls total (2300 != 5000 → exit).
        when(editHistoryRepository.deleteBatchOlderThan(any(LocalDateTime.class), eq(5000)))
                .thenReturn(5000)
                .thenReturn(5000)
                .thenReturn(2300);

        retentionService.purgeOldEditHistory();

        verify(editHistoryRepository, times(3))
                .deleteBatchOlderThan(any(LocalDateTime.class), eq(5000));

        // Capture the cutoff argument and verify it is roughly now - 90d
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(editHistoryRepository, atLeastOnce())
                .deleteBatchOlderThan(cutoffCaptor.capture(), eq(5000));

        LocalDateTime expectedCutoff = LocalDateTime.now().minusDays(90);
        LocalDateTime actualCutoff = cutoffCaptor.getValue();
        long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(expectedCutoff, actualCutoff));
        assertTrue(diffSeconds <= 5,
                "cutoff should be within 5s of now-90d but diff was " + diffSeconds + "s");
    }

    @Test
    void single_batch_when_first_call_returns_lt_batchSize() {
        // First call returns 1234 (< 5000) → loop exits after 1 iteration.
        when(editHistoryRepository.deleteBatchOlderThan(any(LocalDateTime.class), eq(5000)))
                .thenReturn(1234);

        retentionService.purgeOldEditHistory();

        verify(editHistoryRepository, times(1))
                .deleteBatchOlderThan(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void respects_property_override() throws Exception {
        // Override retentionDays from default 90 to 30 via reflection
        setRetentionDays(30);

        when(editHistoryRepository.deleteBatchOlderThan(any(LocalDateTime.class), eq(5000)))
                .thenReturn(0);

        retentionService.purgeOldEditHistory();

        // Capture the cutoff and verify it is roughly now - 30d (not 90d)
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(editHistoryRepository).deleteBatchOlderThan(cutoffCaptor.capture(), eq(5000));

        LocalDateTime expected30d = LocalDateTime.now().minusDays(30);
        LocalDateTime actualCutoff = cutoffCaptor.getValue();
        long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(expected30d, actualCutoff));
        assertTrue(diffSeconds <= 5,
                "cutoff should be within 5s of now-30d but diff was " + diffSeconds + "s");

        // Also verify it is NOT close to now - 90d
        LocalDateTime expected90d = LocalDateTime.now().minusDays(90);
        long diff90 = Math.abs(ChronoUnit.DAYS.between(expected90d, actualCutoff));
        assertTrue(diff90 >= 50,
                "cutoff should be far from now-90d when retentionDays=30 but diff was " + diff90 + " days");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void setRetentionDays(int days) throws Exception {
        Field field = MessageEditHistoryRetentionService.class
                .getDeclaredField("retentionDays");
        field.setAccessible(true);
        field.set(retentionService, days);
    }
}
