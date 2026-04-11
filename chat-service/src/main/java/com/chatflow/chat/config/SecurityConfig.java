package com.chatflow.chat.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ws/**", "/ws-native/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/files/upload").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/chat/rooms/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/chat/rooms/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/chat/rooms/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/chat/rooms/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/chat/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/chat/auth/**").permitAll()
                        .requestMatchers("/api/fhir/**").permitAll()
                        .requestMatchers("/api/fcm/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
