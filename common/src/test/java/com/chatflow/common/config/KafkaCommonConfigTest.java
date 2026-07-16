package com.chatflow.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaCommonConfigTest {

    @Mock
    private ConcurrentKafkaListenerContainerFactoryConfigurer configurer;

    @Mock
    private ConsumerFactory<String, Object> consumerFactory;

    @Mock
    private CommonErrorHandler errorHandler;

    private final KafkaCommonConfig config = new KafkaCommonConfig();

    @Test
    void factory_shouldDelegateToConfigurer() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(configurer, consumerFactory, null);

        assertNotNull(factory, "factory must be created");
        verify(configurer).configure(
                any(ConcurrentKafkaListenerContainerFactory.class),
                any(ConsumerFactory.class));
    }

    @Test
    void factory_shouldAttachErrorHandler_whenProvided() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(configurer, consumerFactory, errorHandler);

        assertNotNull(factory);
        verify(configurer).configure(
                any(ConcurrentKafkaListenerContainerFactory.class),
                any(ConsumerFactory.class));

        // Verify error handler was set via reflection (private field)
        CommonErrorHandler actual = getCommonErrorHandlerViaReflection(factory);
        assertSame(errorHandler, actual, "error handler should be attached to factory");
    }

    @Test
    void factory_shouldNotSetErrorHandler_whenNull() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(configurer, consumerFactory, null);

        assertNotNull(factory);
        // No error handler should be set
        CommonErrorHandler actual = getCommonErrorHandlerViaReflection(factory);
        assertNull(actual, "error handler should not be set when null");
    }

    /**
     * AbstractKafkaListenerContainerFactory stores the error handler in a private field.
     * We use reflection to verify it was set.
     */
    private CommonErrorHandler getCommonErrorHandlerViaReflection(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        try {
            // Field is declared in the abstract superclass, not the concrete class
            var field = factory.getClass().getSuperclass()
                    .getDeclaredField("commonErrorHandler");
            field.setAccessible(true);
            return (CommonErrorHandler) field.get(factory);
        } catch (Exception e) {
            fail("Could not reflectively access commonErrorHandler: " + e.getMessage());
            return null;
        }
    }
}
