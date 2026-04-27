package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberListBroadcaster {

    private final RoomMemberRepository roomMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Fetches the current member list for the given room and broadcasts it
     * to /topic/chat/{roomId}/members via STOMP.
     */
    public void broadcast(String roomId) {
        List<RoomMemberEntity> members = roomMemberRepository.findByRoomId(roomId);
        List<Map<String, Object>> memberList = members.stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("userId", m.getUserId());
                    map.put("username", m.getUsername());
                    map.put("role", m.getRole().name());
                    map.put("mutedUntil", m.getMutedUntil() != null ? m.getMutedUntil().toString() : null);
                    return map;
                })
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "MEMBER_LIST_UPDATED");
        payload.put("members", memberList);
        payload.put("timestamp", LocalDateTime.now().toString());

        messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/members", payload);
    }
}
