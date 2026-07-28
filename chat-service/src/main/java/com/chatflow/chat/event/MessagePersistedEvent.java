package com.chatflow.chat.event;

import com.chatflow.common.dto.ChatMessage;
import lombok.Getter;

import java.util.List;

/**
 * DB 커밋 후 WebSocket 브로드캐스트를 트리거하는 도메인 이벤트.
 * {@link org.springframework.transaction.event.TransactionalEventListener}에서 소비.
 */
@Getter
public class MessagePersistedEvent {

    private final ChatMessage message;

    /**
     * 이 메시지가 실제로 호명한 room_members 사용자명 (발신자 제외).
     *
     * <p>리스너가 본문을 다시 파싱하지 않도록 발행 시점에 확정된 결과를 싣는다.
     * 멘션 대상은 방 멤버 목록과 대조해야만 알 수 있는데(사용자명 문자셋이 자유롭다),
     * 리스너는 커밋 후 비동기라 그 시점의 DB를 다시 읽으면 방금 나간 멤버까지
     * 달라질 수 있다. 멘션 행·FCM·UNREAD_INCREMENT가 항상 같은 목록을 보게 된다.
     */
    private final List<String> mentionedUsernames;

    public MessagePersistedEvent(ChatMessage message) {
        this(message, List.of());
    }

    public MessagePersistedEvent(ChatMessage message, List<String> mentionedUsernames) {
        this.message = message;
        this.mentionedUsernames = mentionedUsernames != null ? List.copyOf(mentionedUsernames) : List.of();
    }
}
