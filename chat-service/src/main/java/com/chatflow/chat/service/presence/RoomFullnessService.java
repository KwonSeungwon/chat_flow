package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.chat.service.room.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Room-capacity gate for join attempts. DM rooms reject non-members,
 * general rooms redirect to a sibling room.
 *
 * <p><b>Side effect:</b> When a general room is full, the caller's
 * {@code ChatMessage.chatRoomId} is mutated to the redirect target. The
 * caller MUST treat the message as moved after this method returns false.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomFullnessService {

    private final ParticipantService participantService;
    private final ChatRoomService chatRoomService;
    private final RoomMemberRepository roomMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @return true if the join should be aborted (DM full, non-member).
     *         false if the caller should proceed -- possibly into the
     *         redirected room (general-room redirect mutates the message).
     */
    public boolean handleIfFull(ChatMessage message, String currentUserId, boolean alreadyJoined) {
        if (!participantService.isRoomFull(message.getChatRoomId())) return false;
        if (alreadyJoined) return false;

        ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);

        if (room != null && room.getRoomType() == RoomType.DIRECT) {
            boolean isExistingMember = !currentUserId.isEmpty() &&
                    roomMemberRepository.existsByRoomIdAndUserId(
                            message.getChatRoomId(), currentUserId);
            if (!isExistingMember) {
                log.warn("DM room {} is full, rejecting non-member {}",
                        message.getChatRoomId(), message.getUsername());
                messagingTemplate.convertAndSend(
                        "/topic/chat/" + message.getChatRoomId() + "/errors",
                        Map.of("type", "ROOM_FULL_DM",
                                "roomId", message.getChatRoomId(),
                                "roomName", room.getName()));
                return true;
            }
            log.info("DM {} full but {} is existing member -- allowing re-entry",
                    message.getChatRoomId(), message.getUsername());
            return false;
        }

        String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
        ChatRoom newRoom = participantService.findOrCreateAvailableRoom(baseName);

        log.info("Room {} full, redirecting user {} to {}",
                message.getChatRoomId(), message.getUsername(), newRoom.getId());
        messagingTemplate.convertAndSend(
                "/topic/chat/" + message.getChatRoomId() + "/errors",
                Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));

        message.setChatRoomId(newRoom.getId());
        return false;
    }
}
