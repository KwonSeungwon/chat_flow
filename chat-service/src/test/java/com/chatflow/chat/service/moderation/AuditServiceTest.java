package com.chatflow.chat.service.moderation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Captor private ArgumentCaptor<OutboxEvent> outboxCaptor;

    private AuditService service;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new AuditService(outboxEventRepository, objectMapper);

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
    void logAccess_saves_outbox_event_with_correct_fields() {
        String userId = "user-1";
        String roomId = "room-42";
        String eventType = AuditEvent.MESSAGE_READ;

        service.logAccess(userId, "alice", roomId, eventType);

        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent saved = outboxCaptor.getValue();

        assertThat(saved.getTopic()).isEqualTo("audit-events");
        assertThat(saved.getEventType()).isEqualTo(eventType);
        assertThat(saved.getPartitionKey()).isEqualTo(roomId);
        assertThat(saved.getAggregateType()).isEqualTo("AuditEvent");
        assertThat(saved.getPayload()).contains(eventType);
        assertThat(saved.getPayload()).contains(userId);
        assertThat(saved.getPayload()).contains(roomId);
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

        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent saved = outboxCaptor.getValue();
        assertThat(saved.getTopic()).isEqualTo("audit-events");
        assertThat(saved.getEventType()).isEqualTo(AuditEvent.ROOM_JOIN);

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }
}
