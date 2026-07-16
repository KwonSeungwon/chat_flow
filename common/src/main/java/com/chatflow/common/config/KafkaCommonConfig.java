package com.chatflow.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Kafka 공통 설정.
 * ProducerFactory/ConsumerFactory/KafkaTemplate은 Spring Boot auto-config가
 * 각 서비스의 YAML(application-*.yml)에서 생성하도록 위임.
 * 여기서는 Boot의 Configurer를 통해 spring.kafka.listener.* 속성(concurrency 등)을
 * 적용하고, DLT 에러 핸들러를 리스너 컨테이너 팩토리에 연결하는 역할을 수행.
 */
@Configuration
public class KafkaCommonConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<String, Object> consumerFactory,
            @Autowired(required = false) CommonErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<Object, Object> rawFactory =
                (ConcurrentKafkaListenerContainerFactory<Object, Object>) (ConcurrentKafkaListenerContainerFactory<?, ?>) factory;
        @SuppressWarnings("unchecked")
        ConsumerFactory<Object, Object> rawConsumerFactory =
                (ConsumerFactory<Object, Object>) (ConsumerFactory<?, ?>) consumerFactory;
        configurer.configure(rawFactory, rawConsumerFactory);
        if (kafkaErrorHandler != null) {
            factory.setCommonErrorHandler(kafkaErrorHandler);
        }
        return factory;
    }
}
