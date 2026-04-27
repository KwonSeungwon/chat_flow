package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.exception.RoomTypeNotSupportedException;
import com.chatflow.chat.exception.SelfTargetNotAllowedException;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomPermissionService {

    private final RoomMemberRepository roomMemberRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * Returns the role of the given user in the given room.
     * Throws PermissionDeniedException if the user is not a member of the room.
     */
    public RoomRole getUserRole(String roomId, String userId) {
        RoomMemberEntity member = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new PermissionDeniedException(
                        "사용자가 채팅방의 멤버가 아닙니다. roomId=" + roomId + ", userId=" + userId));
        return member.getRole();
    }

    /**
     * Ensures the user has one of the allowed roles.
     * Throws PermissionDeniedException if the user's role is not in the allowed set.
     */
    public void requireRole(String roomId, String userId, RoomRole... allowed) {
        RoomRole userRole = getUserRole(roomId, userId);
        boolean hasPermission = Arrays.asList(allowed).contains(userRole);
        if (!hasPermission) {
            throw new PermissionDeniedException(
                    "권한이 부족합니다. 필요 역할: " + Arrays.toString(allowed)
                            + ", 현재 역할: " + userRole);
        }
    }

    /**
     * Ensures the room is not a DM (DIRECT) room.
     * DM rooms do not support operator/moderation features.
     */
    public void requireNotDmRoom(String roomId) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            if (room.getRoomType() == RoomType.DIRECT) {
                throw new RoomTypeNotSupportedException(
                        "DM 채팅방에서는 운영 기능을 사용할 수 없습니다. roomId=" + roomId);
            }
        });
    }

    /**
     * Ensures actor and target are different users.
     */
    public void requireNotSelfTarget(String actorUserId, String targetUserId) {
        if (actorUserId.equals(targetUserId)) {
            throw new SelfTargetNotAllowedException(
                    "자기 자신에게는 이 액션을 수행할 수 없습니다.");
        }
    }
}
