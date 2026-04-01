package com.chatflow.chat.service;

import com.chatflow.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Daily batch: deletes chat messages older than the retention period.
 * Runs at 03:00 KST (18:00 UTC) to minimize user impact.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageRetentionService {

    private final ChatMessageRepository chatMessageRepository;

    @Value("${chatflow.message-retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "0 0 18 * * *") // 03:00 KST = 18:00 UTC
    public void purgeOldMessages() {
        final LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("Message retention: deleting messages older than {} ({}d)", cutoff, retentionDays);

        final int deleted = chatMessageRepository.deleteMessagesOlderThan(cutoff);
        log.info("Message retention: deleted {} messages", deleted);
    }
}
