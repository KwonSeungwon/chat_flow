package com.gossiplog.gateway.security.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.WebSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 제공자 콜백 처리 뒤 프론트로 돌려보낸다. 성공하면 일회용 코드를 붙여 /#/social-callback으로, 실패하면 /#/login?socialError=1로.
 * 세션은 인가 요청 저장에만 쓰고 끝나면 비운다(우리 인증은 JWT).
 */
@Slf4j
@Component
public class SocialLoginHandlers implements ServerAuthenticationSuccessHandler, ServerAuthenticationFailureHandler {

    private final SocialLoginService loginService;
    private final SocialLoginCodeStore codeStore;
    private final String frontendBaseUrl;
    private final ServerRedirectStrategy redirect = new DefaultServerRedirectStrategy();

    public SocialLoginHandlers(SocialLoginService loginService, SocialLoginCodeStore codeStore,
                               @Value("${gossiplog.oauth.frontend-base-url:http://localhost:8000}") String frontendBaseUrl) {
        this.loginService = loginService;
        this.codeStore = codeStore;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange exchange, org.springframework.security.core.Authentication authentication) {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        return Mono.fromCallable(() -> SocialProfileExtractor.extract(registrationId, token.getPrincipal().getAttributes()))
                .flatMap(loginService::login)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("소셜 로그인 결과가 비었다")))
                .flatMap(codeStore::issue)
                .map(code -> callback("social-callback", "code", code))
                .onErrorResume(e -> {
                    log.warn("[oauth] {} login failed after provider callback: {}", registrationId, e.toString());
                    return Mono.just(callback("login", "socialError", "1"));
                })
                .flatMap(uri -> endSession(exchange).then(redirect.sendRedirect(exchange.getExchange(), uri)));
    }

    @Override
    public Mono<Void> onAuthenticationFailure(WebFilterExchange exchange, org.springframework.security.core.AuthenticationException e) {
        log.warn("[oauth] provider callback rejected: {}", e.toString());
        return endSession(exchange).then(redirect.sendRedirect(exchange.getExchange(), callback("login", "socialError", "1")));
    }

    /** Flutter 웹은 해시 라우팅이라 경로를 #/ 뒤에 붙인다. */
    URI callback(String route, String param, String value) {
        String fragment = UriComponentsBuilder.fromPath("/" + route).queryParam(param, value).build().encode().toUriString();
        return URI.create(frontendBaseUrl.replaceAll("/+$", "") + "/#" + fragment);
    }

    private Mono<Void> endSession(WebFilterExchange exchange) {
        return exchange.getExchange().getSession().flatMap(WebSession::invalidate);
    }
}
