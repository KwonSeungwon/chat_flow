package com.gossiplog.common.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** STOMP sendMessage validates ChatMessage; field caps match chat_messages column lengths. */
class ChatMessageValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private ChatMessage valid() {
        ChatMessage m = new ChatMessage();
        m.setChatRoomId("room-1");
        m.setUsername("alice");
        m.setContent("hi");
        m.setType(ChatMessage.MessageType.CHAT);
        return m;
    }

    private void assertRejected(String field, Consumer<ChatMessage> mutate) {
        ChatMessage m = valid();
        mutate.accept(m);
        assertThat(validator.validate(m))
                .extracting(v -> v.getPropertyPath().toString())
                .contains(field);
    }

    @Test
    void validMessageWithMaxLengthFields_passes() {
        ChatMessage m = valid();
        m.setForwardedFrom("f".repeat(200));
        m.setParentMessageId("p".repeat(36));
        m.setFileUrl("u".repeat(512));
        m.setFileName("n".repeat(255));
        m.setFileContentType("t".repeat(100));
        assertThat(validator.validate(m)).isEmpty();
    }

    @Test
    void overlongFields_areRejected() {
        assertRejected("forwardedFrom", m -> m.setForwardedFrom("f".repeat(201)));
        assertRejected("parentMessageId", m -> m.setParentMessageId("p".repeat(37)));
        assertRejected("fileUrl", m -> m.setFileUrl("u".repeat(513)));
        assertRejected("fileName", m -> m.setFileName("n".repeat(256)));
        assertRejected("fileContentType", m -> m.setFileContentType("t".repeat(101)));
    }
}
