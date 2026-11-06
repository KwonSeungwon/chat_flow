package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.repository.SocialAccountRepository;
import com.gossiplog.gateway.repository.UserRepository;
import com.gossiplog.gateway.security.AuthService.AuthResponse;
import com.gossiplog.gateway.security.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * registration을 넣었을 때 보안 체인이 제공자 인가 요청으로 리다이렉트하는지, 코드 교환 엔드포인트가
 * 인증 없이 열려 있는지 확인한다. 값은 운영 application-prod.yml과 같은 모양이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.client.registration.google.client-id=g-id",
        "spring.security.oauth2.client.registration.google.client-secret=g-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,profile",
        "spring.security.oauth2.client.registration.google.redirect-uri=https://app.example.com/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.naver.client-id=n-id",
        "spring.security.oauth2.client.registration.naver.client-secret=n-secret",
        "spring.security.oauth2.client.registration.naver.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.naver.client-authentication-method=client_secret_post",
        "spring.security.oauth2.client.registration.naver.redirect-uri=https://app.example.com/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.kakao.client-id=k-id",
        "spring.security.oauth2.client.registration.kakao.client-secret=k-secret",
        "spring.security.oauth2.client.registration.kakao.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.kakao.client-authentication-method=client_secret_post",
        "spring.security.oauth2.client.registration.kakao.scope=profile_nickname",
        "spring.security.oauth2.client.registration.kakao.redirect-uri=https://app.example.com/login/oauth2/code/{registrationId}",
})
@ActiveProfiles("test")
class OAuth2LoginFlowTest {

    @Autowired private WebTestClient web;
    @MockBean private UserRepository userRepository;
    @MockBean private SocialAccountRepository socialAccountRepository;
    @MockBean private ReactiveStringRedisTemplate redis;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private SocialLoginCodeStore codeStore;

    @SuppressWarnings("unchecked")
    private URI authorize(String registrationId) {
        // 소셜 로그인 시작도 IP 레이트리밋을 탄다. 한도 안쪽 횟수를 돌려준다.
        when(redis.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(reactor.core.publisher.Flux.just(1L));
        return web.get().uri("/oauth2/authorization/" + registrationId).exchange()
                .expectStatus().isFound()
                .returnResult(Void.class).getResponseHeaders().getLocation();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> m = new java.util.HashMap<>();
        for (String kv : uri.getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            m.put(p[0], java.net.URLDecoder.decode(p.length > 1 ? p[1] : "", java.nio.charset.StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void kakao_redirectsToKakaoAuthorizeWithRestKeyAndExactRedirectUri() {
        URI loc = authorize("kakao");
        assertEquals("kauth.kakao.com", loc.getHost());
        assertEquals("/oauth/authorize", loc.getPath());
        Map<String, String> q = query(loc);
        assertEquals("k-id", q.get("client_id"));
        assertEquals("code", q.get("response_type"));
        assertEquals("profile_nickname", q.get("scope"));
        assertEquals("https://app.example.com/login/oauth2/code/kakao", q.get("redirect_uri"));
        assertTrue(q.containsKey("state"));
    }

    @Test
    void naver_redirectsToNaverAuthorize() {
        URI loc = authorize("naver");
        assertEquals("nid.naver.com", loc.getHost());
        assertEquals("n-id", query(loc).get("client_id"));
        assertEquals("https://app.example.com/login/oauth2/code/naver", query(loc).get("redirect_uri"));
    }

    @Test
    void google_redirectsToGoogleWithOpenIdProfileOnly() {
        URI loc = authorize("google");
        assertEquals("accounts.google.com", loc.getHost());
        assertEquals("openid profile", query(loc).get("scope"));
    }

    @Test
    void exchange_isPublic_andReturnsSessionForValidCode() {
        AuthResponse resp = new AuthResponse("jwt", "u1", "땅콩", null);
        when(codeStore.redeem("good")).thenReturn(Mono.just(resp));

        web.post().uri("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", "good")).exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).isEqualTo(resp);
    }

    @Test
    void exchange_unknownOrReusedCode_is400() {
        when(codeStore.redeem("used")).thenReturn(Mono.empty());

        web.post().uri("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", "used")).exchange()
                .expectStatus().isBadRequest();
    }
}
