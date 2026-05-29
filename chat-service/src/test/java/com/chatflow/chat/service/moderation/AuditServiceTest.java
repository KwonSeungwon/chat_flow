package com.chatflow.chat.service.moderation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    private AuditService service;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new AuditService(kafkaTemplate, objectMapper);

        auditLogger = (Logger) LoggerFactory.getLogger(AuditService.class);
        auditLogger.setLevel(Level.DEBUG);

        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void logAccess_emits_structured_log_with_userId_roomId_event() {
        service.logAccess("user-1", "alice", "room-42", AuditEvent.MESSAGE_READ);

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.DEBUG);

        String formatted = events.get(0).getFormattedMessage();
        assertThat(formatted).contains("user-1");
        assertThat(formatted).contains("room-42");
        assertThat(formatted).contains(AuditEvent.MESSAGE_READ);
    }

    @Test
    void logAccess_handles_null_username() {
        assertThatCode(() ->
                service.logAccess("user-2", null, "room-7", AuditEvent.ROOM_JOIN)
        ).doesNotThrowAnyException();

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }
}
