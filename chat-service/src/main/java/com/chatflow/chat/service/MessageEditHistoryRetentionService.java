package com.chatflow.chat.service;

import com.chatflow.chat.repository.MessageEditHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Daily batch: deletes message-edit-history rows older than the retention
 * period. Mirrors {@link MessageRetentionService} for chat_messages but on
 * a longer default window (90 days) — edit history is small per row and
 * audit value lasts longer than ephemeral chat content.
 *
 * Runs at 03:30 KST (18:30 UTC), staggered after MessageRetentionService
 * so the two batches don't contend on the same DB connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageEditHistoryRetentionService {

    private final MessageEditHistoryRepository editHistoryRepository;

    @Value("${chatflow.edit-history-retention-days:90}")
    private int retentionDays;

    private static final int BATCH_SIZE = 5000;

    @Scheduled(cron = "0 30 18 * * *") // 03:30 KST = 18:30 UTC
    public void purgeOldEditHistory() {
        final LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("Edit-history retention: deleting rows older than {} ({}d)", cutoff, retentionDays);

        int totalDeleted = 0;
        int deleted;
        do {
            deleted = editHistoryRepository.deleteBatchOlderThan(cutoff, BATCH_SIZE);
            totalDeleted += deleted;
            if (deleted > 0) {
                log.info("Edit-history retention: batch deleted {} rows (total: {})", deleted, totalDeleted);
            }
        } while (deleted == BATCH_SIZE);

        log.info("Edit-history retention: completed, total deleted {} rows", totalDeleted);
    }
}
