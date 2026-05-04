package com.chatflow.search.service;

import com.chatflow.search.util.SearchConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanSearchServiceTest {

    @Test
    void excludedMessageTypes_shouldContainJoinLeaveSystem() {
        assertThat(SearchConstants.EXCLUDED_MESSAGE_TYPES)
                .containsExactlyInAnyOrder("JOIN", "LEAVE", "SYSTEM");
    }

    @Test
    void chatMessagesIndex_shouldEqualChatMessages() {
        assertThat(SearchConstants.CHAT_MESSAGES_INDEX).isEqualTo("chat_messages");
    }

    @Test
    void highlightTags_shouldBeMarkTags() {
        assertThat(SearchConstants.HIGHLIGHT_PRE_TAG).isEqualTo("<mark>");
        assertThat(SearchConstants.HIGHLIGHT_POST_TAG).isEqualTo("</mark>");
    }

    @Test
    void excludedMessageTypes_shouldBeImmutable() {
        assertThat(SearchConstants.EXCLUDED_MESSAGE_TYPES).isUnmodifiable();
    }
}
