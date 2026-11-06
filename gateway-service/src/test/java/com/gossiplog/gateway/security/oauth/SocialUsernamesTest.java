package com.gossiplog.gateway.security.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialUsernamesTest {

    private static final String RULE = "^[A-Za-z0-9가-힣_.-]{2,20}$";

    @Test
    void keepsAllowedCharacters() {
        assertEquals("땅콩_01", SocialUsernames.base("땅콩_01"));
    }

    @Test
    void dropsDisallowedCharactersAndSpaces() {
        assertEquals("KimMinsu", SocialUsernames.base(" Kim Min-su!? ".replace("-", "")));
        assertEquals("홍길동", SocialUsernames.base("홍길동 😀"));
    }

    @Test
    void cutsToSixteenSoASuffixStillFits() {
        String b = SocialUsernames.base("a".repeat(40));
        assertEquals(16, b.length());
    }

    @Test
    void emptyOrTooShort_fallsBackToUser() {
        assertEquals("user", SocialUsernames.base(null));
        assertEquals("user", SocialUsernames.base("😀"));
        assertEquals("user", SocialUsernames.base("a"));
    }

    @Test
    void candidate_firstIsBase_thenSuffixed_andAlwaysMatchesSignupRule() {
        assertEquals("땅콩", SocialUsernames.candidate("땅콩", 0));
        for (int i = 1; i < 6; i++) {
            String c = SocialUsernames.candidate("a".repeat(16), i);
            assertTrue(c.matches(RULE), c);
            assertTrue(c.matches("^a{16}_\\d{3}$"), c);
        }
    }
}
