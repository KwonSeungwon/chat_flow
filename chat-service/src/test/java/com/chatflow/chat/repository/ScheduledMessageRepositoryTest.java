package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional claim / fail updates used by per-row scheduled delivery.
 */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class ScheduledMessageRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 3, 0);

    @Autowired
    private ScheduledMessageRepository repository;

    private ScheduledMessageEntity save(ScheduledMessageStatus status) {
        return repository.save(ScheduledMessageEntity.builder()
                .chatRoomId("room-1").userId("user-1").username("alice")
                .content("hi").scheduledAt(NOW.minusMinutes(1)).status(status)
                .build());
    }

    @Test
    void claim_flipsPendingToProcessing_onlyOnce() {
        ScheduledMessageEntity row = save(ScheduledMessageStatus.PENDING);
        long versionBefore = row.getVersion();

        assertThat(repository.claim(row.getId(), NOW)).isEqualTo(1);
        // A second replica racing for the same row gets nothing.
        assertThat(repository.claim(row.getId(), NOW)).isZero();

        ScheduledMessageEntity reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ScheduledMessageStatus.PROCESSING);
        assertThat(reloaded.getVersion()).isGreaterThan(versionBefore);
    }

    @Test
    void claim_ignoresCanceledRow() {
        ScheduledMessageEntity row = save(ScheduledMessageStatus.CANCELED);
        assertThat(repository.claim(row.getId(), NOW)).isZero();
    }

    @Test
    void markFailed_setsReason_forPendingRow() {
        ScheduledMessageEntity row = save(ScheduledMessageStatus.PENDING);

        assertThat(repository.markFailed(row.getId(), "kafka down", NOW)).isEqualTo(1);

        ScheduledMessageEntity reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ScheduledMessageStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).isEqualTo("kafka down");
    }

    @Test
    void markFailed_neverOverwritesSentRow() {
        ScheduledMessageEntity row = save(ScheduledMessageStatus.SENT);

        assertThat(repository.markFailed(row.getId(), "late failure", NOW)).isZero();

        assertThat(repository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduledMessageStatus.SENT);
    }
}
