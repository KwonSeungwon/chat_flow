package com.chatflow.chat.service;

import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shouldRequestAISummary rule in MessageSenderService.
 *
 * The method is private, so we exercise it via a package-private test seam
 * (shouldRequestAISummaryForTest). No Spring context is required — all
 * collaborator slots are null because send() is never called here.
 */
class MessageSenderServiceTest {

    private final MessageSenderService service =
            new MessageSenderService(null, null, null, null, null, null,
                    new SimpleMeterRegistry());

    @Test
    void shouldRequestAISummary_returns_false_for_non_chat_types() {
        String longContent = "x".repeat(150);
        for (MessageType t : MessageType.values()) {
            if (t == MessageType.CHAT) continue;
            ChatMessage msg = ChatMessage.builder()
                    .type(t).content(longContent).build();
            assertThat(service.shouldRequestAISummaryForTest(msg))
                    .as("type=%s should not request summary", t)
                    .isFalse();
        }
    }

    @Test
    void shouldRequestAISummary_short_chat_returns_false() {
        ChatMessage msg = ChatMessage.builder()
                .type(MessageType.CHAT).content("short").build();
        assertThat(service.shouldRequestAISummaryForTest(msg)).isFalse();
    }

    @Test
    void shouldRequestAISummary_long_chat_returns_true() {
        ChatMessage msg = ChatMessage.builder()
                .type(MessageType.CHAT).content("x".repeat(150)).build();
        assertThat(service.shouldRequestAISummaryForTest(msg)).isTrue();
    }

    @Test
    void shouldRequestAISummary_chat_with_null_content_returns_false() {
        ChatMessage msg = ChatMessage.builder()
                .type(MessageType.CHAT).content(null).build();
        assertThat(service.shouldRequestAISummaryForTest(msg)).isFalse();
    }
}
