package com.chatflow.chat.event;

import com.chatflow.common.dto.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DB 커밋 후 WebSocket 브로드캐스트를 트리거하는 도메인 이벤트.
 * {@link org.springframework.transaction.event.TransactionalEventListener}에서 소비.
 */
@Getter
@AllArgsConstructor
public class MessagePersistedEvent {
    private final ChatMessage message;
}
