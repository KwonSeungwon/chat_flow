package com.chatflow.chat.repository;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class RoomMemberRepositoryRoleTest {

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    private static final String ROOM_ID = "room-1";

    @BeforeEach
    void setUp() {
        roomMemberRepository.deleteAll();
    }

    @Test
    void defaultRole_isMember() {
        // given — save without explicitly setting role
        RoomMemberEntity member = RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId("user-1")
                .username("Alice")
                .joinedAt(LocalDateTime.now())
                .build();

        // when
        RoomMemberEntity saved = roomMemberRepository.saveAndFlush(member);

        // then
        assertThat(saved.getRole()).isEqualTo(RoomRole.MEMBER);
    }

    @Test
    void saveWithOwnerRole_readsBackCorrectly() {
        // given
        RoomMemberEntity owner = RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId("user-owner")
                .username("Bob")
                .role(RoomRole.OWNER)
                .joinedAt(LocalDateTime.now())
                .build();

        // when
        roomMemberRepository.saveAndFlush(owner);
        RoomMemberEntity found = roomMemberRepository.findById(
                new RoomMemberEntity.RoomMemberId(ROOM_ID, "user-owner")
        ).orElseThrow();

        // then
        assertThat(found.getRole()).isEqualTo(RoomRole.OWNER);
    }

    @Test
    void mutedUntil_persistsAndReadsBack() {
        // given
        LocalDateTime muteExpiry = LocalDateTime.now().plusMinutes(30);
        RoomMemberEntity member = RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId("user-muted")
                .username("Charlie")
                .role(RoomRole.MEMBER)
                .mutedUntil(muteExpiry)
                .joinedAt(LocalDateTime.now())
                .build();

        // when
        roomMemberRepository.saveAndFlush(member);
        RoomMemberEntity found = roomMemberRepository.findById(
                new RoomMemberEntity.RoomMemberId(ROOM_ID, "user-muted")
        ).orElseThrow();

        // then
        assertThat(found.getMutedUntil()).isEqualTo(muteExpiry);
    }

    @Test
    void mutedUntil_defaultIsNull() {
        // given
        RoomMemberEntity member = RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId("user-normal")
                .username("Diana")
                .joinedAt(LocalDateTime.now())
                .build();

        // when
        roomMemberRepository.saveAndFlush(member);
        RoomMemberEntity found = roomMemberRepository.findById(
                new RoomMemberEntity.RoomMemberId(ROOM_ID, "user-normal")
        ).orElseThrow();

        // then
        assertThat(found.getMutedUntil()).isNull();
    }
}
