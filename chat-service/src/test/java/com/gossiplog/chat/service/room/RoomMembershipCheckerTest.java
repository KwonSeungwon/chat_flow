package com.gossiplog.chat.service.room;

import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.entity.RoomRole;
import com.gossiplog.chat.repository.RoomMemberRepository;
import com.gossiplog.chat.service.room.RoomMembershipChecker.MembershipResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomMembershipCheckerTest {

    @Mock private RoomMemberRepository roomMemberRepository;

    private RoomMembershipChecker checker;

    private static final String ROOM_ID = "room-abc";
    private static final String USER_ID = "user-123";

    @BeforeEach
    void setUp() {
        checker = new RoomMembershipChecker(roomMemberRepository);
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
        @DisplayName("room creator without a room_members row is NOT a member (no createdBy bridge)")
        void roomCreatorWithoutRow_returnsFalse() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);

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

    @Nested
    @DisplayName("findMember")
    class FindMemberTests {

        @Test
        @DisplayName("returns the entity when user has a room_members row")
        void memberRow_returnsEntity() {
            RoomMemberEntity entity = RoomMemberEntity.builder()
                    .roomId(ROOM_ID).userId(USER_ID).username("alice")
                    .role(RoomRole.MEMBER).joinedAt(LocalDateTime.now()).build();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.of(entity));

            Optional<MembershipResult> result = checker.findMember(ROOM_ID, USER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().entity()).isSameAs(entity);
        }

        @Test
        @DisplayName("returns empty when user has no room_members row (creator or not)")
        void noRow_returnsEmpty() {
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.empty());

            assertThat(checker.findMember(ROOM_ID, USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null roomId")
        void nullRoomId_returnsEmpty() {
            assertThat(checker.findMember(null, USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null userId")
        void nullUserId_returnsEmpty() {
            assertThat(checker.findMember(ROOM_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for blank userId")
        void blankUserId_returnsEmpty() {
            assertThat(checker.findMember(ROOM_ID, "  ")).isEmpty();
        }

        @Test
        @DisplayName("findMember never calls existsBy — no duplicate lookup on the send path")
        void findMember_does_not_call_existsBy() {
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(Optional.empty());

            checker.findMember(ROOM_ID, USER_ID);

            // The send-path optimisation: findMember must NOT also call existsBy
            verify(roomMemberRepository, never()).existsByRoomIdAndUserId(ROOM_ID, USER_ID);
        }
    }
}
