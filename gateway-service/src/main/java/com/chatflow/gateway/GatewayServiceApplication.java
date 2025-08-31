package com.chatflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("chat-service", r -> r.path("/api/chat/**")
                        .uri("http://localhost:8080"))
                .route("ai-summary-service", r -> r.path("/api/ai-summary/**")
                        .uri("http://localhost:8081"))
                .route("search-service", r -> r.path("/api/search/**")
                        .uri("http://localhost:8082"))
                .route("websocket", r -> r.path("/ws/**")
                        .uri("ws://localhost:8080"))
                .build();
    }
}