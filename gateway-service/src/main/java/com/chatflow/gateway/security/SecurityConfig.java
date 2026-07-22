package com.chatflow.gateway.security;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationWebFilter jwtAuthenticationWebFilter;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
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
                        .pathMatchers("/api/chat/auth/**").permitAll()
                        // Mutation auth endpoints require authentication (blacklist-gated by JwtAuthenticationWebFilter)
                        .pathMatchers(HttpMethod.PUT, "/api/auth/profile", "/api/auth/password").authenticated()
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers("/ws-native", "/ws-native/**").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
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
