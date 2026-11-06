package com.gossiplog.gateway.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공통 application.yml의 소셜 로그인 provider 엔드포인트를 제공자 문서 값으로 고정한다.
 * 카카오: developers.kakao.com/docs/ko/kakaologin/rest-api (인가 코드·토큰·사용자 정보 조회).
 */
class OAuth2ProviderConfigTest {

    private static OAuth2ClientProperties props;

    @BeforeAll
    static void load() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        props = new Binder(ConfigurationPropertySources.get(env))
                .bind("spring.security.oauth2.client", OAuth2ClientProperties.class)
                .get();
    }

    @Test
    void kakaoProvider_matchesKakaoRestApiDocs() {
        OAuth2ClientProperties.Provider kakao = props.getProvider().get("kakao");
        assertEquals("https://kauth.kakao.com/oauth/authorize", kakao.getAuthorizationUri());
        assertEquals("https://kauth.kakao.com/oauth/token", kakao.getTokenUri());
        assertEquals("https://kapi.kakao.com/v2/user/me", kakao.getUserInfoUri());
        // 회원번호는 응답 최상위 id(Long)다. 닉네임은 kakao_account.profile.nickname(중첩)이라 식별자로 쓰지 않는다.
        assertEquals("id", kakao.getUserNameAttribute());
    }

    @Test
    void naverProvider_isStillConfigured() {
        assertEquals("https://nid.naver.com/oauth2.0/authorize",
                props.getProvider().get("naver").getAuthorizationUri());
    }

    @Test
    void noClientRegistrationYet_soOAuth2ClientAutoConfigStaysOff() {
        assertTrue(props.getRegistration().isEmpty());
    }
}
