package com.chatflow.chat.repository;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Shared JPA test configuration that excludes Kafka/Redis auto-configuration.
 * <p>
 * chat-service's main class uses a broad @ComponentScan that pulls in config beans
 * (SecurityConfig, TokenBlacklistService, etc.) with external dependencies (Redis, Kafka,
 * MeterRegistry). This config restricts scanning to only entity/repository packages so
 * @DataJpaTest can run with an embedded H2 database without those dependencies.
 */
@EnableAutoConfiguration(exclude = {
        KafkaAutoConfiguration.class,
        RedisAutoConfiguration.class
})
@EntityScan(basePackages = "com.chatflow.chat.entity")
@EnableJpaRepositories(basePackages = "com.chatflow.chat.repository")
class RepositoryTestConfig {
}
