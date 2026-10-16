package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The room-list query must mirror RoomAccessPolicy#isVisible. */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class ChatRoomRepositoryVisibleRoomsTest {

    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private RoomMemberRepository roomMemberRepository;

    private static final String ME = "user-me";

    @BeforeEach
    void setUp() {
        roomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        save("public", RoomType.GENERAL, false, null, t.plusMinutes(1));
        save("password", RoomType.GENERAL, false, "$2a$10$hash", t.plusMinutes(2));
        save("handoff", RoomType.HANDOFF, false, null, t.plusMinutes(3));
        save("private-other", RoomType.GENERAL, true, null, t.plusMinutes(4));
        save("private-mine", RoomType.GENERAL, true, null, t.plusMinutes(5));
        save("dm-other", RoomType.DIRECT, false, null, t.plusMinutes(6));
        save("dm-mine", RoomType.DIRECT, false, null, t.plusMinutes(7));
        member("private-mine", ME);
        member("dm-mine", ME);
        member("dm-other", "someone-else");
    }

    private void save(String id, RoomType type, boolean isPrivate, String password, LocalDateTime createdAt) {
        chatRoomRepository.save(ChatRoom.builder()
                .id(id).name(id).roomType(type).isPrivate(isPrivate).password(password)
                .createdAt(createdAt).build());
    }

    private void member(String roomId, String userId) {
        roomMemberRepository.save(RoomMemberEntity.builder()
                .roomId(roomId).userId(userId).username(userId)
                .role(RoomRole.MEMBER).joinedAt(LocalDateTime.now()).build());
    }

    @Test
    void returnsPublicPasswordHandoffAndOwnRooms_newestFirst() {
        List<String> ids = chatRoomRepository.findVisibleOrderByLastActivity(ME)
                .stream().map(ChatRoom::getId).toList();

        assertThat(ids).containsExactly("dm-mine", "private-mine", "handoff", "password", "public");
    }

    @Test
    void strangerSeesNoDmsOrPrivateRooms() {
        List<String> ids = chatRoomRepository.findVisibleOrderByLastActivity("stranger")
                .stream().map(ChatRoom::getId).toList();

        assertThat(ids).containsExactly("handoff", "password", "public");
    }
}
