package com.chatflow.chat.repository;

import com.chatflow.chat.entity.RoomBanEntity;
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
class RoomBanRepositoryTest {

    @Autowired
    private RoomBanRepository roomBanRepository;

    private static final String ROOM_ID = "room-1";
    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";
    private static final String BANNER = "owner-1";

    @BeforeEach
    void setUp() {
        roomBanRepository.deleteAll();
    }

    @Test
    void existsByRoomIdAndUserId_returnsTrueWhenBanExists() {
        // given
        roomBanRepository.save(RoomBanEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_A)
                .bannedBy(BANNER)
                .bannedAt(LocalDateTime.now())
                .build());

        // when & then
        assertThat(roomBanRepository.existsByRoomIdAndUserId(ROOM_ID, USER_A)).isTrue();
    }

    @Test
    void existsByRoomIdAndUserId_returnsFalseWhenNoBan() {
        // when & then
        assertThat(roomBanRepository.existsByRoomIdAndUserId(ROOM_ID, USER_A)).isFalse();
    }

    @Test
    void findByRoomId_returnsAllBansForRoom() {
        // given
        roomBanRepository.save(RoomBanEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_A)
                .bannedBy(BANNER)
                .reason("spam")
                .bannedAt(LocalDateTime.now())
                .build());
        roomBanRepository.save(RoomBanEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_B)
                .bannedBy(BANNER)
                .bannedAt(LocalDateTime.now())
                .build());
        // ban in a different room — should not be returned
        roomBanRepository.save(RoomBanEntity.builder()
                .roomId("room-other")
                .userId(USER_A)
                .bannedBy(BANNER)
                .bannedAt(LocalDateTime.now())
                .build());

        // when
        List<RoomBanEntity> bans = roomBanRepository.findByRoomId(ROOM_ID);

        // then
        assertThat(bans).hasSize(2);
        assertThat(bans).allMatch(b -> b.getRoomId().equals(ROOM_ID));
    }

    @Test
    void deleteByRoomIdAndUserId_removesOnlyTargetRow() {
        // given
        roomBanRepository.save(RoomBanEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_A)
                .bannedBy(BANNER)
                .bannedAt(LocalDateTime.now())
                .build());
        roomBanRepository.save(RoomBanEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_B)
                .bannedBy(BANNER)
                .bannedAt(LocalDateTime.now())
                .build());

        // when
        roomBanRepository.deleteByRoomIdAndUserId(ROOM_ID, USER_A);

        // then
        assertThat(roomBanRepository.existsByRoomIdAndUserId(ROOM_ID, USER_A)).isFalse();
        assertThat(roomBanRepository.existsByRoomIdAndUserId(ROOM_ID, USER_B)).isTrue();
    }
}
