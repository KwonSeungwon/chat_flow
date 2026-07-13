package com.chatflow.chat.service.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MentionExtractorTest {

    @Test
    void extracts_ascii_korean_and_underscore_names() {
        // The pattern allows dots, so "@under_score" without trailing punctuation
        assertThat(MentionExtractor.extract("hi @bob and @김간호사, also @under_score ok"))
                .containsExactly("bob", "김간호사", "under_score");
    }

    @Test
    void deduplicates_and_preserves_order() {
        assertThat(MentionExtractor.extract("@a @b @a")).containsExactly("a", "b");
    }

    @Test
    void empty_when_no_at_or_null() {
        assertThat(MentionExtractor.extract(null)).isEmpty();
        assertThat(MentionExtractor.extract("no mentions here")).isEmpty();
    }

    @Test
    void caps_name_length_at_30() {
        String longName = "a".repeat(31);
        // pattern matches only the first 30 chars
        assertThat(MentionExtractor.extract("@" + longName))
                .containsExactly("a".repeat(30));
    }
}
