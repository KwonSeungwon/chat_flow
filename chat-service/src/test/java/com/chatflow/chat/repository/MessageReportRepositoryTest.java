package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageReportEntity;
import com.chatflow.chat.entity.ReportReason;
import com.chatflow.chat.entity.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class MessageReportRepositoryTest {

    @Autowired
    private MessageReportRepository messageReportRepository;

    private static final String ROOM_ID = "room-1";
    private static final String REPORTER_A = "user-a";
    private static final String REPORTER_B = "user-b";

    @BeforeEach
    void setUp() {
        messageReportRepository.deleteAll();
    }

    @Test
    void findByRoomIdAndStatus_returnsOnlyPendingInDescOrder() {
        // given
        LocalDateTime now = LocalDateTime.now();

        messageReportRepository.save(MessageReportEntity.builder()
                .messageId("msg-1")
                .roomId(ROOM_ID)
                .reportedBy(REPORTER_A)
                .reason(ReportReason.SPAM)
                .status(ReportStatus.PENDING)
                .createdAt(now.minusMinutes(10))
                .build());

        messageReportRepository.save(MessageReportEntity.builder()
                .messageId("msg-2")
                .roomId(ROOM_ID)
                .reportedBy(REPORTER_B)
                .reason(ReportReason.HARASSMENT)
                .status(ReportStatus.PENDING)
                .createdAt(now.minusMinutes(5))
                .build());

        messageReportRepository.save(MessageReportEntity.builder()
                .messageId("msg-3")
                .roomId(ROOM_ID)
                .reportedBy(REPORTER_A)
                .reason(ReportReason.INAPPROPRIATE)
                .status(ReportStatus.RESOLVED)
                .resolvedBy("mod-1")
                .resolvedAt(now)
                .createdAt(now.minusMinutes(1))
                .build());

        // when
        List<MessageReportEntity> pending =
                messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(ROOM_ID, ReportStatus.PENDING);

        // then
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getMessageId()).isEqualTo("msg-2"); // newer first
        assertThat(pending.get(1).getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void countByReportedByAndCreatedAtAfter_forRateLimit() {
        // given
        LocalDateTime now = LocalDateTime.now();

        // 3 reports within the last minute
        for (int i = 0; i < 3; i++) {
            messageReportRepository.save(MessageReportEntity.builder()
                    .messageId("msg-" + i)
                    .roomId(ROOM_ID)
                    .reportedBy(REPORTER_A)
                    .reason(ReportReason.OTHER)
                    .status(ReportStatus.PENDING)
                    .createdAt(now.minusSeconds(30 + i))
                    .build());
        }

        // 1 report older than 1 minute
        messageReportRepository.save(MessageReportEntity.builder()
                .messageId("msg-old")
                .roomId(ROOM_ID)
                .reportedBy(REPORTER_A)
                .reason(ReportReason.SPAM)
                .status(ReportStatus.PENDING)
                .createdAt(now.minusMinutes(5))
                .build());

        // report by a different user
        messageReportRepository.save(MessageReportEntity.builder()
                .messageId("msg-other")
                .roomId(ROOM_ID)
                .reportedBy(REPORTER_B)
                .reason(ReportReason.SPAM)
                .status(ReportStatus.PENDING)
                .createdAt(now)
                .build());

        // when
        long countRecent = messageReportRepository.countByReportedByAndCreatedAtAfter(
                REPORTER_A, now.minusMinutes(1));

        // then — only the 3 recent reports by REPORTER_A
        assertThat(countRecent).isEqualTo(3);
    }
}
