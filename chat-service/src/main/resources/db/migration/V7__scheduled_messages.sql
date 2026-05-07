-- V7: Scheduled messages table for the schedule-send feature.
-- Status lifecycle: PENDING -> SENT (success) | CANCELED (user) | FAILED (error)
-- Pending rows are polled every 30s by ScheduledMessageService.

CREATE TABLE IF NOT EXISTS scheduled_messages (
    id              BIGSERIAL PRIMARY KEY,
    chat_room_id    VARCHAR(50)  NOT NULL,
    user_id         VARCHAR(36)  NOT NULL,
    username        VARCHAR(50)  NOT NULL,
    content         TEXT         NOT NULL,
    scheduled_at    TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    sent_message_id VARCHAR(36),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    error_message   TEXT,
    CONSTRAINT fk_scheduled_messages_room
        FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_scheduled_messages_status_due
    ON scheduled_messages (status, scheduled_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_scheduled_messages_user
    ON scheduled_messages (user_id, status, scheduled_at DESC);
