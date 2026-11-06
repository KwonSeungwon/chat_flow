package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.entity.SocialProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SocialProfileExtractorTest {

    @Test
    void google_usesSubAndName() {
        SocialProfile p = SocialProfileExtractor.extract("google", Map.of("sub", "1098", "name", "Kim"));
        assertEquals(new SocialProfile(SocialProvider.GOOGLE, "1098", "Kim"), p);
    }

    @Test
    void naver_readsNestedResponse() {
        // 네이버 회원 프로필 조회 응답: {resultcode, message, response: {id, nickname}}
        SocialProfile p = SocialProfileExtractor.extract("naver", Map.of(
                "resultcode", "00", "message", "success",
                "response", Map.of("id", "abcDEF-123", "nickname", "땅콩")));
        assertEquals(new SocialProfile(SocialProvider.NAVER, "abcDEF-123", "땅콩"), p);
    }

    @Test
    void kakao_numericIdAndNestedNickname() {
        // 카카오 사용자 정보 조회 응답: id(Long), kakao_account.profile.nickname
        SocialProfile p = SocialProfileExtractor.extract("kakao", Map.of(
                "id", 1285016924429472463L,
                "kakao_account", Map.of("profile", Map.of("nickname", "홍길동"))));
        assertEquals(new SocialProfile(SocialProvider.KAKAO, "1285016924429472463", "홍길동"), p);
    }

    @Test
    void kakao_withoutNicknameConsent_hasNullName() {
        SocialProfile p = SocialProfileExtractor.extract("kakao", Map.of("id", 42, "kakao_account", Map.of()));
        assertEquals("42", p.providerUserId());
        assertNull(p.displayName());
    }

    @Test
    void missingId_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SocialProfileExtractor.extract("naver", Map.of("response", Map.of("nickname", "x"))));
    }

    @Test
    void unknownProvider_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SocialProfileExtractor.extract("github", Map.of("id", 1)));
    }
}
