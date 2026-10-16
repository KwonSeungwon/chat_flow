package com.chatflow.common.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("KafkaErrorConfig — retry classification of the shared DefaultErrorHandler")
class KafkaErrorConfigTest {

    private BinaryExceptionClassifier classifier;

    enum Kind { A }

    record Payload(Kind kind, int count) { }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CommonErrorHandler handler = new KafkaErrorConfig().kafkaErrorHandler(mock(KafkaTemplate.class));
        // DefaultErrorHandler#getClassifier is protected; it is what decides retry vs. straight-to-DLT.
        classifier = ReflectionTestUtils.invokeMethod(handler, "getClassifier");
    }

    /** true = retried with backoff, false = recovered (DLT) immediately. */
    private boolean retryable(Throwable listenerFailure) {
        // The container wraps whatever the listener throws in ListenerExecutionFailedException.
        return classifier.classify(new ListenerExecutionFailedException("listener failed", listenerFailure));
    }

    private static <T extends Throwable> T captureMappingFailure(String json, Class<T> type) {
        try {
            new ObjectMapper().readValue(json, Payload.class);
        } catch (Throwable t) {
            assertThat(t).isInstanceOf(type);
            return type.cast(t);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    @Test
    @DisplayName("unknown enum value (InvalidFormatException) inside BatchListenerFailedException is not retried")
    void unknownEnumInBatchFailure_notRetryable() {
        InvalidFormatException cause = captureMappingFailure("{\"kind\":\"NEW_TYPE\"}", InvalidFormatException.class);

        assertThat(retryable(new BatchListenerFailedException("bad record", cause, 3))).isFalse();
    }

    @Test
    @DisplayName("MismatchedInputException is not retried")
    void mismatchedInput_notRetryable() {
        MismatchedInputException cause = captureMappingFailure("{\"count\":[1]}", MismatchedInputException.class);

        assertThat(retryable(cause)).isFalse();
    }

    @Test
    @DisplayName("JsonParseException is still not retried")
    void parseError_notRetryable() {
        JsonParseException cause = captureMappingFailure("{not json", JsonParseException.class);

        assertThat(retryable(new BatchListenerFailedException("bad record", cause, 0))).isFalse();
    }

    @Test
    @DisplayName("transient infrastructure failures (e.g. ES IOException) are still retried")
    void ioFailure_retryable() {
        assertThat(retryable(new IllegalStateException("bulk failed", new IOException("ES down")))).isTrue();
    }
}
