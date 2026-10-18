package com.gossiplog.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS의 단일 출처. 게이트웨이 라우트와 자체 컨트롤러 모두 이 필터가 처리하므로
 * spring.cloud.gateway.globalcors는 사용하지 않는다 (중복 헤더 방지).
 * 허용 origin은 gossiplog.cors.allowed-origins(쉼표 구분 패턴)로 지정하며 기본값은 운영 도메인 하나뿐이다.
 */
@Configuration
public class CorsConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE) // 401/429 같은 조기 응답에도 CORS 헤더가 붙도록 가장 먼저
    public CorsWebFilter corsWebFilter(
            @Value("${gossiplog.cors.allowed-origins:https://app.gossiplog.com}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
