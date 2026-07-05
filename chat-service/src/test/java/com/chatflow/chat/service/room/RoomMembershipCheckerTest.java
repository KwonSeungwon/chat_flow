package com.chatflow.chat.service.room;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomMembershipCheckerTest {

    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private ChatRoomRepository chatRoomRepository;

    private RoomMembershipChecker checker;

    private static final String ROOM_ID = "room-abc";
    private static final String USER_ID = "user-123";

    @BeforeEach
    void setUp() {
        checker = new RoomMembershipChecker(roomMemberRepository, chatRoomRepository);
    }

    @Nested
    @DisplayName("isMember")
    class IsMemberTests {

        @Test
        @DisplayName("returns true when user has a room_members row")
        void memberRow_returnsTrue() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);

            assertThat(checker.isMember(ROOM_ID, USER_ID)).isTrue();
        }

        @Test
        @DisplayName("returns true when user is the room creator (legacy bridge)")
        void roomCreator_returnsTrue() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);
            ChatRoom room = ChatRoom.builder().createdBy(USER_ID).build();
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

            assertThat(checker.isMember(ROOM_ID, USER_ID)).isTrue();
        }

        @Test
        @DisplayName("returns false when user is neither member nor creator")
        void notMemberNorCreator_returnsFalse() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);
            ChatRoom room = ChatRoom.builder().createdBy("someone-else").build();
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

            assertThat(checker.isMember(ROOM_ID, USER_ID)).isFalse();
        }

        @Test
        @DisplayName("returns false when room does not exist")
        void roomNotFound_returnsFalse() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

            assertThat(checker.isMember(ROOM_ID, USER_ID)).isFalse();
        }

        @Test
        @DisplayName("returns false for null roomId")
        void nullRoomId_returnsFalse() {
            assertThat(checker.isMember(null, USER_ID)).isFalse();
        }

        @Test
        @DisplayName("returns false for null userId")
        void nullUserId_returnsFalse() {
            assertThat(checker.isMember(ROOM_ID, null)).isFalse();
        }

        @Test
        @DisplayName("returns false for blank userId")
        void blankUserId_returnsFalse() {
            assertThat(checker.isMember(ROOM_ID, "  ")).isFalse();
        }
    }
}
