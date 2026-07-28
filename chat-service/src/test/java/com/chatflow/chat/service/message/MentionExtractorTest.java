package com.chatflow.chat.service.message;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MentionExtractorTest {

    private static final List<String> MEMBERS =
            List.of("bob", "김간호사", "under_score", "phill.park");

    @Test
    void resolves_ascii_korean_underscore_and_dotted_names() {
        assertThat(MentionExtractor.resolve(
                "hi @bob and @김간호사, also @under_score ok", MEMBERS))
                .containsExactly("bob", "김간호사", "under_score");
    }

    @Test
    void deduplicates_and_preserves_first_appearance_order() {
        assertThat(MentionExtractor.resolve("@bob @김간호사 @bob", MEMBERS))
                .containsExactly("bob", "김간호사");
    }

    @Test
    void empty_when_no_at_or_null_or_no_members() {
        assertThat(MentionExtractor.resolve(null, MEMBERS)).isEmpty();
        assertThat(MentionExtractor.resolve("no mentions here", MEMBERS)).isEmpty();
        assertThat(MentionExtractor.resolve("@bob", List.of())).isEmpty();
        assertThat(MentionExtractor.resolve("@bob", null)).isEmpty();
    }

    @Test
    void ignores_names_that_are_not_room_members() {
        assertThat(MentionExtractor.resolve("@stranger hello", MEMBERS)).isEmpty();
    }

    /**
     * The whole point of the change: usernames are unvalidated at registration
     * (gateway AuthService.register accepts any string), so a charset regex can
     * never enumerate them. These all silently notified nobody before.
     */
    @Nested
    class NamesTheOldCharsetRegexTruncated {

        @Test
        void hyphenated_uuid_fallback_username() {
            // RoomMembershipService substitutes the userId when username is blank,
            // so 36-char hyphenated names exist in prod today.
            String uuid = "bd515969-a57e-4cc1-b72d-6b2508b483d0";
            assertThat(MentionExtractor.resolve("@" + uuid + " ping", List.of(uuid)))
                    .containsExactly(uuid);
        }

        @Test
        void name_containing_a_space() {
            assertThat(MentionExtractor.resolve("@John Doe are you there", List.of("John Doe")))
                    .containsExactly("John Doe");
        }

        @Test
        void name_longer_than_thirty_characters() {
            String long_ = "a".repeat(45);
            assertThat(MentionExtractor.resolve("@" + long_, List.of(long_)))
                    .containsExactly(long_);
        }

        @Test
        void accented_and_non_latin_names() {
            assertThat(MentionExtractor.resolve("@José hi @Ольга", List.of("José", "Ольга")))
                    .containsExactly("José", "Ольга");
        }
    }

    @Nested
    class MatchBoundaries {

        @Test
        void prefers_the_longest_matching_member() {
            assertThat(MentionExtractor.resolve("@bobby hi", List.of("bob", "bobby")))
                    .containsExactly("bobby");
        }

        @Test
        void does_not_match_a_member_that_is_only_a_prefix_of_the_typed_name() {
            // "bob" must not be notified when someone writes "@bobby" and no
            // member named bobby exists.
            assertThat(MentionExtractor.resolve("@bobby hi", List.of("bob"))).isEmpty();
        }

        @Test
        void allows_trailing_punctuation() {
            assertThat(MentionExtractor.resolve("@bob, @김간호사! @phill.park.", MEMBERS))
                    .containsExactly("bob", "김간호사", "phill.park");
        }

        @Test
        void matches_at_start_after_newline_and_after_a_bracket() {
            assertThat(MentionExtractor.resolve("@bob", MEMBERS)).containsExactly("bob");
            assertThat(MentionExtractor.resolve("line\n@bob", MEMBERS)).containsExactly("bob");
            assertThat(MentionExtractor.resolve("(@bob)", MEMBERS)).containsExactly("bob");
        }

        @Test
        void does_not_treat_an_email_address_as_a_mention() {
            assertThat(MentionExtractor.resolve("mail me at bob@phill.park thanks", MEMBERS))
                    .isEmpty();
        }

        @Test
        void is_case_sensitive_to_match_the_database_lookup() {
            assertThat(MentionExtractor.resolve("@BOB", MEMBERS)).isEmpty();
        }

        @Test
        void korean_particle_attached_directly_is_not_a_match() {
            // "@김간호사님" — no space, so the name is ambiguous. The UI always
            // inserts a trailing space, and the old regex failed here too.
            assertThat(MentionExtractor.resolve("@김간호사님 안녕", MEMBERS)).isEmpty();
        }

        @Test
        void skips_past_a_match_rather_than_rescanning_inside_it() {
            assertThat(MentionExtractor.resolve("@a@b done", List.of("a@b", "b")))
                    .containsExactly("a@b");
        }
    }

    @Test
    void tolerates_null_and_blank_entries_in_the_member_list() {
        List<String> messy = java.util.Arrays.asList("bob", null, "", "   ");
        assertThat(MentionExtractor.resolve("@bob hi", messy)).containsExactly("bob");
    }

    @Test
    void takes_an_unordered_collection_and_still_orders_by_appearance() {
        // 호출자(MentionTargets)는 List를 넘기지만 시그니처는 Collection이다.
        // 순서 없는 Set을 받아도 결과는 "본문 등장 순서 + 최장일치"여야 한다.
        Set<String> unordered = new LinkedHashSet<>(List.of("bob", "bobby", "김간호사"));
        assertThat(MentionExtractor.resolve("@김간호사 님, @bobby 도 봐주세요", unordered))
                .containsExactly("김간호사", "bobby");
    }
}
