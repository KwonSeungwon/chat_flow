package com.chatflow.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Kafka 공통 설정.
 * ProducerFactory/ConsumerFactory/KafkaTemplate은 Spring Boot auto-config가
 * 각 서비스의 YAML(application-*.yml)에서 생성하도록 위임.
 * 여기서는 DLT 에러 핸들러를 리스너 컨테이너 팩토리에 연결하는 역할만 수행.
 */
@Configuration
public class KafkaCommonConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Autowired(required = false) CommonErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        if (kafkaErrorHandler != null) {
            factory.setCommonErrorHandler(kafkaErrorHandler);
        }
        return factory;
    }
}
