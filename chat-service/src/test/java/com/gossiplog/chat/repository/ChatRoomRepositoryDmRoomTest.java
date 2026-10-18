package com.gossiplog.chat.repository;

import com.gossiplog.chat.entity.ChatRoom;
import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.entity.RoomRole;
import com.gossiplog.chat.entity.RoomType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enum-literal JPQL comparisons on roomType run through RoomTypeConverter.
 * Same-named GENERAL and legacy HANDOFF rows prove the type filter, not the name, decides.
 */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class ChatRoomRepositoryDmRoomTest {

    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private RoomMemberRepository roomMemberRepository;
    @Autowired private EntityManager entityManager;

    private static final String DM_NAME = "DM:alice,bob";

    @BeforeEach
    void setUp() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        chatRoomRepository.save(ChatRoom.builder()
                .id("dm-ab").name(DM_NAME).roomType(RoomType.DIRECT).createdAt(t).build());
        chatRoomRepository.save(ChatRoom.builder()
                .id("general-ab").name(DM_NAME).roomType(RoomType.GENERAL).createdAt(t.plusMinutes(1)).build());
        member("dm-ab", "u-alice", "alice");
        member("dm-ab", "u-bob", "bob");
        entityManager.flush();
        entityManager.createNativeQuery("INSERT INTO chat_rooms "
                        + "(id, name, room_type, is_private, allow_invites, created_at) "
                        + "VALUES ('legacy-ab', '" + DM_NAME + "', 'HANDOFF', FALSE, TRUE, CURRENT_TIMESTAMP)")
                .executeUpdate();
        entityManager.clear();
    }

    private void member(String roomId, String userId, String username) {
        roomMemberRepository.save(RoomMemberEntity.builder()
                .roomId(roomId).userId(userId).username(username)
                .role(RoomRole.MEMBER).joinedAt(LocalDateTime.now()).build());
    }

    @Test
    void findDmRoom_returnsOnlyTheDirectRoomForThePair_inEitherOrder() {
        assertThat(chatRoomRepository.findDmRoom(DM_NAME, "DM:bob,alice"))
                .extracting(ChatRoom::getId).containsExactly("dm-ab");
        assertThat(chatRoomRepository.findDmRoom("DM:bob,alice", DM_NAME))
                .extracting(ChatRoom::getId).containsExactly("dm-ab");
    }

    @Test
    void findDmRoomByMembers_findsTheDirectRoomBothUsersBelongTo_inEitherOrder() {
        assertThat(chatRoomRepository.findDmRoomByMembers("u-alice", "u-bob"))
                .extracting(ChatRoom::getId).containsExactly("dm-ab");
        assertThat(chatRoomRepository.findDmRoomByMembers("u-bob", "u-alice"))
                .extracting(ChatRoom::getId).containsExactly("dm-ab");
    }

    @Test
    void findDmRoomByMembers_ignoresRoomsOnlyOneOfThemBelongsTo_andNonDirectRooms() {
        member("general-ab", "u-alice", "alice");
        member("general-ab", "u-carol", "carol");
        entityManager.flush();

        assertThat(chatRoomRepository.findDmRoomByMembers("u-alice", "u-carol")).isEmpty();
    }

    @Test
    void findDmRoom_returnsNothingForAnotherPair() {
        assertThat(chatRoomRepository.findDmRoom("DM:alice,carol", "DM:carol,alice")).isEmpty();
    }

    @Test
    void findVisible_strangerSeesGeneralAndLegacyRoomsButNotTheDm() {
        List<String> ids = chatRoomRepository.findVisibleOrderByLastActivity("u-stranger")
                .stream().map(ChatRoom::getId).toList();
        assertThat(ids).containsExactlyInAnyOrder("general-ab", "legacy-ab");
    }

    @Test
    void findVisible_memberAlsoSeesTheDm() {
        List<String> ids = chatRoomRepository.findVisibleOrderByLastActivity("u-alice")
                .stream().map(ChatRoom::getId).toList();
        assertThat(ids).containsExactlyInAnyOrder("dm-ab", "general-ab", "legacy-ab");
    }
}
