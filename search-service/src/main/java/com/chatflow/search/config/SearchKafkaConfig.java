package com.chatflow.search.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Batch listener container factory for search indexing.
 *
 * <p>Same Boot-configured consumer settings (spring.kafka.*) and the same shared
 * DLT error handler ({@code KafkaErrorConfig}) as the common single-record factory,
 * but delivers one poll as a {@code List}. With the default BATCH ack mode the
 * offsets of a poll are committed only after the listener returns, i.e. after the
 * bulk write succeeded.</p>
 */
@Configuration
public class SearchKafkaConfig {

    public static final String BATCH_LISTENER_CONTAINER_FACTORY = "searchBatchListenerContainerFactory";

    @Bean(name = BATCH_LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> searchBatchListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Autowired(required = false) CommonErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        if (kafkaErrorHandler != null) {
            factory.setCommonErrorHandler(kafkaErrorHandler);
        }
        return factory;
    }
}
