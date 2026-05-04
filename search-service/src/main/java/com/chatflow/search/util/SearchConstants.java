package com.chatflow.search.util;

import java.util.List;

public final class SearchConstants {

    private SearchConstants() {
    }

    public static final String CHAT_MESSAGES_INDEX = "chat_messages";
    public static final String HIGHLIGHT_PRE_TAG = "<mark>";
    public static final String HIGHLIGHT_POST_TAG = "</mark>";

    public static final List<String> EXCLUDED_MESSAGE_TYPES =
            List.of("JOIN", "LEAVE", "SYSTEM");
}
