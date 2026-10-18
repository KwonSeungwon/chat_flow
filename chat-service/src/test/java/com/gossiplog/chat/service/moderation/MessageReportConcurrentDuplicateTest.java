package com.gossiplog.chat.service.moderation;

import com.gossiplog.chat.entity.ChatMessageEntity;
import com.gossiplog.chat.entity.MessageReportEntity;
import com.gossiplog.chat.entity.ReportReason;
import com.gossiplog.chat.entity.ReportStatus;
import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.repository.ChatMessageRepository;
import com.gossiplog.chat.repository.MessageReportRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
import com.gossiplog.chat.repository.RoomMemberRepository;
import com.gossiplog.chat.service.RoomPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * A duplicate report that loses the race to the UNIQUE(message_id, reported_by)
 * constraint returns the winner's id instead of a 500.
 */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@Import(MessageReportService.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MessageReportConcurrentDuplicateTest {

    @Autowired private MessageReportService service;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private RoomMemberRepository roomMemberRepository;
    @SpyBean private MessageReportRepository messageReportRepository;
    @MockBean private RoomPermissionService roomPermissionService;

    @AfterEach
    void cleanUp() {
        messageReportRepository.deleteAll();
        roomMemberRepository.deleteAll();
        chatMessageRepository.deleteAll();
    }

    @Test
    void duplicateLosingTheUniqueRace_returnsTheWinnersId() {
        chatMessageRepository.save(ChatMessageEntity.builder()
                .messageId("m-1").chatRoomId("r-1").userId("author").username("author")
                .content("hi").type("CHAT").timestamp(LocalDateTime.now())
                .deleted(false).pinned(false).build());
        roomMemberRepository.save(RoomMemberEntity.builder()
                .roomId("r-1").userId("reporter").username("reporter").joinedAt(LocalDateTime.now()).build());
        Long winner = messageReportRepository.save(MessageReportEntity.builder()
                .messageId("m-1").roomId("r-1").reportedBy("reporter")
                .reason(ReportReason.SPAM).status(ReportStatus.PENDING).build()).getId();
        // The idempotency pre-check misses (the winner committed after it ran).
        // Later lookups read the row from the database.
        doReturn(Optional.empty()).doAnswer(inv -> messageReportRepository.findById(winner))
                .when(messageReportRepository).findByMessageIdAndReportedBy("m-1", "reporter");

        Long id = service.submitReport("m-1", "reporter", ReportReason.SPAM, null);

        assertThat(id).isEqualTo(winner);
        assertThat(messageReportRepository.count()).isEqualTo(1);
    }
}
