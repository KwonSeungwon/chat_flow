package com.chatflow.chat.event;

import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    @Async("persistenceExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        ChatMessage message = event.getMessage();
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        log.debug("Broadcast {} to room {} after commit", message.getMessageId(), message.getChatRoomId());
    }
}
