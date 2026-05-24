package com.chatflow.chat.service;

import com.chatflow.chat.repository.ChatMessageRepository;
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
 * Unit tests for MessageRetentionService covering the batch-loop
 * drain semantics and configurable retention-day cutoff.
 */
@ExtendWith(MockitoExtension.class)
class MessageRetentionServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;

    private MessageRetentionService retentionService;

    @BeforeEach
    void setUp() throws Exception {
        retentionService = new MessageRetentionService(chatMessageRepository);
        // @Value is not processed in unit tests; set the default manually
        Field retentionDaysField = MessageRetentionService.class
                .getDeclaredField("retentionDays");
        retentionDaysField.setAccessible(true);
        retentionDaysField.set(retentionService, 7);
    }

    // -- Batch-loop semantics -----------------------------------------------

    @Test
    void purges_messages_older_than_retentionDays_in_batches() {
        // do/while loop continues while deleted == BATCH_SIZE (5000).
        // Returns: 5000, 5000, 2300 -> 3 calls total (2300 != 5000 -> exit).
        when(chatMessageRepository.deleteBatchOlderThan(any(LocalDateTime.class), eq(5000)))
                .thenReturn(5000)
                .thenReturn(5000)
                .thenReturn(2300);

        retentionService.purgeOldMessages();

        verify(chatMessageRepository, times(3))
                .deleteBatchOlderThan(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void single_batch_when_first_call_under_threshold() {
        // First call returns 1234 (< 5000) -> loop exits after 1 iteration.
        when(chatMessageRepository.deleteBatchOlderThan(any(LocalDateTime.class), eq(5000)))
                .thenReturn(1234);

        retentionService.purgeOldMessages();

        verify(chatMessageRepository, times(1))
                .deleteBatchOlderThan(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void cutoff_is_now_minus_retentionDays() {
        when(chatMessageRepository.deleteBatchOlderThan(any(LocalDateTime.class), eq(5000)))
                .thenReturn(0);

        retentionService.purgeOldMessages();

        // Capture the cutoff argument and verify it is roughly now - 7d
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chatMessageRepository).deleteBatchOlderThan(cutoffCaptor.capture(), eq(5000));

        LocalDateTime expectedCutoff = LocalDateTime.now().minusDays(7);
        LocalDateTime actualCutoff = cutoffCaptor.getValue();
        long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(expectedCutoff, actualCutoff));
        assertTrue(diffSeconds <= 5,
                "cutoff should be within 5s of now-7d but diff was " + diffSeconds + "s");
    }

    // -- Helpers ------------------------------------------------------------

    private void setRetentionDays(int days) throws Exception {
        Field field = MessageRetentionService.class
                .getDeclaredField("retentionDays");
        field.setAccessible(true);
        field.set(retentionService, days);
    }
}
