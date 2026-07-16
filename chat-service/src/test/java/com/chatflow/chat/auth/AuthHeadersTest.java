package com.chatflow.chat.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthHeadersTest {

    // ── Constants ────────────────────────────────────────────────

    @Test
    void constants_matchExpectedHeaderNames() {
        assertThat(AuthHeaders.X_USER_ID).isEqualTo("X-User-Id");
        assertThat(AuthHeaders.X_USERNAME).isEqualTo("X-Username");
    }

    // ── decodeUsername ───────────────────────────────────────────

    @Test
    void decodeUsername_null_returnsNull() {
        assertThat(AuthHeaders.decodeUsername(null)).isNull();
    }

    @Test
    void decodeUsername_plainAscii_returnsUnchanged() {
        assertThat(AuthHeaders.decodeUsername("bob")).isEqualTo("bob");
    }

    @Test
    void decodeUsername_urlEncodedKorean_decodesCorrectly() {
        // "김" URL-encoded as UTF-8
        assertThat(AuthHeaders.decodeUsername("%EA%B9%80")).isEqualTo("김");
    }

    @Test
    void decodeUsername_urlEncodedKoreanPhrase_decodesCorrectly() {
        // "김철수" URL-encoded
        String encoded = "%EA%B9%80%EC%B2%A0%EC%88%98";
        assertThat(AuthHeaders.decodeUsername(encoded)).isEqualTo("김철수");
    }

    @Test
    void decodeUsername_malformedPercentEncoding_returnsRawString() {
        // "%ZZ" is not valid percent-encoding — should return raw, not throw
        String malformed = "%ZZ";
        assertThat(AuthHeaders.decodeUsername(malformed)).isEqualTo(malformed);
    }

    @Test
    void decodeUsername_emptyString_returnsEmpty() {
        assertThat(AuthHeaders.decodeUsername("")).isEqualTo("");
    }

    @Test
    void decodeUsername_mixedEncodedAndPlain_decodesCorrectly() {
        // "hello 김" with space as %20
        String encoded = "hello%20%EA%B9%80";
        assertThat(AuthHeaders.decodeUsername(encoded)).isEqualTo("hello 김");
    }
}
