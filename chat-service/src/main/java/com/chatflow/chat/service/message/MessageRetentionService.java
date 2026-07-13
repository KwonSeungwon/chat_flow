package com.chatflow.chat.service.message;

import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
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
    private final MessageMentionRepository messageMentionRepository;

    @Value("${chatflow.message-retention-days:7}")
    private int retentionDays;

    private static final int BATCH_SIZE = 5000;

    @Scheduled(cron = "0 0 18 * * *") // 03:00 KST = 18:00 UTC
    public void purgeOldMessages() {
        final LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("Message retention: deleting messages older than {} ({}d)", cutoff, retentionDays);

        int totalDeleted = 0;
        int deleted;
        do {
            deleted = chatMessageRepository.deleteBatchOlderThan(cutoff, BATCH_SIZE);
            totalDeleted += deleted;
            if (deleted > 0) {
                log.info("Message retention: batch deleted {} messages (total: {})", deleted, totalDeleted);
            }
        } while (deleted == BATCH_SIZE);

        // 멘션 행은 메시지 timestamp를 created_at으로 공유하므로 같은 cutoff로
        // 삭제하면 정확히 purge된 메시지의 멘션만 제거된다 — 남겨두면 list()는
        // 드롭하는데 unreadCount는 계속 세는 고아 행이 된다.
        int mentionsDeleted = messageMentionRepository.deleteByCreatedAtBefore(cutoff);
        if (mentionsDeleted > 0) {
            log.info("Message retention: deleted {} orphaned mention rows", mentionsDeleted);
        }

        log.info("Message retention: completed, total deleted {} messages", totalDeleted);
    }
}
