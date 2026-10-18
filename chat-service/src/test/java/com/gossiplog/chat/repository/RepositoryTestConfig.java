package com.gossiplog.chat.repository;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Shared JPA test configuration that excludes Redis auto-configuration.
 * <p>
 * chat-service's main class uses a broad @ComponentScan that pulls in config beans
 * (SecurityConfig, TokenBlacklistService, etc.) with external dependencies (Redis,
 * MeterRegistry). This config restricts scanning to only entity/repository packages so
 * @DataJpaTest can run with an embedded H2 database without those dependencies.
 * <p>
 * Public so game repository tests in {@code com.gossiplog.chat.game.*} can use it.
 */
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class
})
@EntityScan(basePackages = {"com.gossiplog.chat.entity", "com.gossiplog.chat.game.entity"})
@EnableJpaRepositories(basePackages = {"com.gossiplog.chat.repository", "com.gossiplog.chat.game.repository"})
public class RepositoryTestConfig {
}
