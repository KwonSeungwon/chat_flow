package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.entity.SocialProvider;
import com.gossiplog.gateway.security.AuthService.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialLoginHandlersTest {

    @Mock private SocialLoginService loginService;
    @Mock private SocialLoginCodeStore codeStore;
    @Mock private WebFilterChain chain;
    private SocialLoginHandlers handlers;
    private MockServerWebExchange exchange;
    private WebSession session;

    @BeforeEach
    void setUp() {
        handlers = new SocialLoginHandlers(loginService, codeStore, "https://app.example.com/");
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/kakao"));
        session = exchange.getSession().block();
        session.getAttributes().put("x", "y");
        session.start();
    }

    private static OAuth2AuthenticationToken kakaoToken() {
        DefaultOAuth2User user = new DefaultOAuth2User(List.of(),
                Map.of("id", 4012L, "kakao_account", Map.of("profile", Map.of("nickname", "홍길동"))), "id");
        return new OAuth2AuthenticationToken(user, List.of(), "kakao");
    }

    private String location() {
        return exchange.getResponse().getHeaders().getLocation().toString();
    }

    @Test
    void callback_putsRouteAfterHashForFlutterHashRouting() {
        assertEquals("https://app.example.com/#/social-callback?code=a-b_c",
                handlers.callback("social-callback", "code", "a-b_c").toString());
    }

    @Test
    void success_redirectsWithOneTimeCode_andEndsSession() {
        AuthResponse resp = new AuthResponse("jwt", "u1", "홍길동", null);
        when(loginService.login(new SocialProfile(SocialProvider.KAKAO, "4012", "홍길동"))).thenReturn(Mono.just(resp));
        when(codeStore.issue(resp)).thenReturn(Mono.just("CODE123"));

        StepVerifier.create(handlers.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), kakaoToken()))
                .verifyComplete();

        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertEquals("https://app.example.com/#/social-callback?code=CODE123", location());
        assertFalse(session.isStarted() && session.getAttributes().containsKey("x"));
    }

    @Test
    void success_butLoginEmpty_stillRedirectsToLoginWithError() {
        when(loginService.login(any())).thenReturn(Mono.empty());

        StepVerifier.create(handlers.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), kakaoToken()))
                .verifyComplete();

        assertEquals("https://app.example.com/#/login?socialError=1", location());
        verify(codeStore, never()).issue(any());
    }

    @Test
    void success_butLoginFails_redirectsToLoginWithError() {
        when(loginService.login(any())).thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(handlers.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), kakaoToken()))
                .verifyComplete();

        assertEquals("https://app.example.com/#/login?socialError=1", location());
    }

    @Test
    void providerFailure_redirectsToLoginWithError_andEndsSession() {
        StepVerifier.create(handlers.onAuthenticationFailure(new WebFilterExchange(exchange, chain),
                        new OAuth2AuthenticationException(new OAuth2Error("access_denied"))))
                .verifyComplete();

        assertEquals("https://app.example.com/#/login?socialError=1", location());
        assertFalse(session.isStarted() && session.getAttributes().containsKey("x"));
    }
}
