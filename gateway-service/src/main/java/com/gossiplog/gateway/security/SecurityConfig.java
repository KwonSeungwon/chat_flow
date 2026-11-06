package com.gossiplog.gateway.security;

import com.gossiplog.gateway.security.oauth.SocialLoginHandlers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * JWT 필터는 @Component로 등록하지 않는다 — WebFilter 빈은 서블릿 체인에 자동 등록되어
     * Security 체인에서 한 번 더 실행되기 때문. 여기서 한 번만 생성해 Security 체인에만 연결한다.
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwtUtil jwtUtil,
            TokenBlacklistService tokenBlacklistService,
            ObjectProvider<ReactiveClientRegistrationRepository> clientRegistrations,
            SocialLoginHandlers socialLoginHandlers,
            @Value("${GATEWAY_INTERNAL_SECRET:}") String gatewayInternalSecret) {
        JwtAuthenticationWebFilter jwtAuthenticationWebFilter =
                new JwtAuthenticationWebFilter(jwtUtil, tokenBlacklistService, gatewayInternalSecret);
        // 소셜 로그인은 registration(client id)이 설정된 환경에서만 켠다. test·local 기본값은 꺼짐.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    // 제공자 토큰은 쓰지 않는다. 세션에 두면 성공 핸들러가 세션을 끝낼 때 같이 사라진다(기본값은 메모리에 계속 쌓인다).
                    .authorizedClientRepository(new WebSessionServerOAuth2AuthorizedClientRepository())
                    .authenticationSuccessHandler(socialLoginHandlers)
                    .authenticationFailureHandler(socialLoginHandlers));
        }
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return Mono.empty();
                        })
                )
                .authorizeExchange(exchanges -> exchanges
                        // Auth: only unauthenticated endpoints are permitAll
                        .pathMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/social/exchange").permitAll()
                        .pathMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        // Mutation auth endpoints require authentication (blacklist-gated by JwtAuthenticationWebFilter)
                        .pathMatchers(HttpMethod.PUT, "/api/auth/profile", "/api/auth/password").authenticated()
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers("/ws-native", "/ws-native/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // Prometheus scrape(클러스터 내부). 운영 ingress는 /api, /ws만 gateway로 보내고 / 는 frontend nginx라 외부 노출 없음.
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/api/fallback/**").permitAll()
                        // FCM unsubscribe-all is token-only (the FCM device token identifies the
                        // device; Firebase verifies ownership) and destructive only against the
                        // caller's own pushes. It must be callable AFTER the session JWT has expired
                        // so logout/expiry cleanup can stop pushes — hence permitAll, not authenticated.
                        .pathMatchers(HttpMethod.POST, "/api/fcm/unsubscribe-all").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/files/**").authenticated()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyExchange().authenticated()
                )
                .addFilterBefore(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
