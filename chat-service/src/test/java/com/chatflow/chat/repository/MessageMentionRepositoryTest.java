package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageMentionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class MessageMentionRepositoryTest {

    @Autowired
    private MessageMentionRepository mentionRepository;

    private static final String USER_ID = "user-1";
    private static final String ROOM_ID = "room-1";

    @BeforeEach
    void setUp() {
        mentionRepository.deleteAll();
    }

    @Test
    void findByMentionedUserIdAndCreatedAtAfter_returnsDescOrder() {
        // given
        LocalDateTime now = LocalDateTime.now();
        mentionRepository.save(mention("msg-1", now.minusHours(2)));
        mentionRepository.save(mention("msg-2", now.minusHours(1)));
        mentionRepository.save(mention("msg-3", now.minusDays(10))); // outside window

        // when
        List<MessageMentionEntity> result = mentionRepository
                .findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                        USER_ID, now.minusDays(1));

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMessageId()).isEqualTo("msg-2"); // newer first
        assertThat(result.get(1).getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void countByMentionedUserIdAndReadFalseAndCreatedAtAfter() {
        // given
        LocalDateTime now = LocalDateTime.now();
        mentionRepository.save(mention("msg-1", now.minusHours(1)));       // unread
        mentionRepository.save(readMention("msg-2", now.minusHours(2)));   // read
        mentionRepository.save(mention("msg-3", now.minusDays(10)));       // outside window

        // when
        long count = mentionRepository.countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
                USER_ID, now.minusDays(1));

        // then
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Transactional
    void markRead_updatesOnlyTargetRow() {
        // given
        LocalDateTime now = LocalDateTime.now();
        mentionRepository.save(mention("msg-1", now.minusHours(1)));
        mentionRepository.save(mention("msg-2", now.minusHours(2)));

        // when
        int updated = mentionRepository.markRead(USER_ID, "msg-1");

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(mentionRepository.countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
                USER_ID, now.minusDays(1))).isEqualTo(1);
    }

    @Test
    @Transactional
    void markAllRead_updatesAllUnreadInWindow() {
        // given
        LocalDateTime now = LocalDateTime.now();
        mentionRepository.save(mention("msg-1", now.minusHours(1)));
        mentionRepository.save(mention("msg-2", now.minusHours(2)));
        mentionRepository.save(readMention("msg-3", now.minusHours(3)));

        // when
        int updated = mentionRepository.markAllRead(USER_ID, now.minusDays(1));

        // then
        assertThat(updated).isEqualTo(2);
        assertThat(mentionRepository.countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
                USER_ID, now.minusDays(1))).isZero();
    }

    private MessageMentionEntity mention(String messageId, LocalDateTime createdAt) {
        return MessageMentionEntity.builder()
                .messageId(messageId)
                .roomId(ROOM_ID)
                .mentionedUserId(USER_ID)
                .mentionedUsername("testuser")
                .fromUsername("sender")
                .createdAt(createdAt)
                .read(false)
                .build();
    }

    private MessageMentionEntity readMention(String messageId, LocalDateTime createdAt) {
        return MessageMentionEntity.builder()
                .messageId(messageId)
                .roomId(ROOM_ID)
                .mentionedUserId(USER_ID)
                .mentionedUsername("testuser")
                .fromUsername("sender")
                .createdAt(createdAt)
                .read(true)
                .build();
    }
}
