package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.room.RoomBanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Pre-join ban gate. Extracted from UserPresenceService.checkBanGate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BanCheckService {

    private final RoomBanService roomBanService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @return true if the user is banned and join should be aborted.
     *         As a side effect, sends a ROOM_BANNED error to the rejected
     *         user's own queue ({@code /user/queue/errors}) so only they
     *         see the rejection -- never the whole room.
     */
    public boolean checkBanGate(String userId, String chatRoomId, String username) {
        if (!userId.isEmpty() && roomBanService.isBanned(chatRoomId, userId)) {
            log.warn("User {} attempted to join banned room {}", username, chatRoomId);
            messagingTemplate.convertAndSendToUser(
                    userId,
                    "/queue/errors",
                    Map.of("type", "ROOM_BANNED", "roomId", chatRoomId));
            return true;
        }
        return false;
    }
}
