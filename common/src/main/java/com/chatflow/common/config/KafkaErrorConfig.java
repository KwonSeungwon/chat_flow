package com.chatflow.common.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
public class KafkaErrorConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    log.error("메시지 처리 실패 → DLQ 전송: topic={}, key={}, error={}",
                            record.topic(), record.key(), ex.getMessage());
                    return new org.apache.kafka.common.TopicPartition(
                            record.topic() + ".DLT", record.partition());
                });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, createExponentialBackOff());

        errorHandler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonParseException.class,
                org.apache.kafka.common.errors.SerializationException.class
        );

        return errorHandler;
    }

    private ExponentialBackOff createExponentialBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxElapsedTime(30_000L);
        return backOff;
    }
}
