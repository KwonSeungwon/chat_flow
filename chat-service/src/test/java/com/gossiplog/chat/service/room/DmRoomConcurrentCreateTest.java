package com.gossiplog.chat.service.room;

import com.gossiplog.chat.entity.ChatRoom;
import com.gossiplog.chat.entity.RoomType;
import com.gossiplog.chat.repository.ChatRoomRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * Two users opening the same DM at once: the request that loses the DM-name
 * UNIQUE index (V2 in prod; a plain unique index stands in on H2) gets the
 * winner's room back instead of an error.
 */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@Import(DmRoomService.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DmRoomConcurrentCreateTest {

    @Autowired private DmRoomService service;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private ChatRoomRepository chatRoomRepository;
    @MockBean private RoomCacheEvictor roomCacheEvictor;

    @BeforeEach
    void uniqueDmName() {
        jdbc.execute("CREATE UNIQUE INDEX uq_test_dm_name ON chat_rooms(name)");
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP INDEX IF EXISTS uq_test_dm_name");
        chatRoomRepository.deleteAll();
    }

    @Test
    void creatorLosingTheNameRace_getsTheWinnersRoom() {
        chatRoomRepository.save(ChatRoom.builder().id("winner").name("DM:alice,bob")
                .roomType(RoomType.DIRECT).maxParticipants(2).participantCount(0)
                .createdAt(LocalDateTime.now()).build());
        // Our lookups ran before the winner committed.
        doReturn(List.of())
                .doAnswer(inv -> List.of(chatRoomRepository.findById("winner").orElseThrow()))
                .when(chatRoomRepository).findDmRoom(anyString(), anyString());

        ChatRoom room = service.createOrFindDmRoom("u-alice", "alice", "u-bob", "bob");

        assertThat(room.getId()).isEqualTo("winner");
        assertThat(chatRoomRepository.count()).isEqualTo(1);
    }
}
