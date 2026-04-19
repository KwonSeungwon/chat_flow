-- V1__baseline.sql
-- 운영 중인 chat-service 스키마의 baseline.
-- 기존 DB에 이미 테이블이 존재하므로 IF NOT EXISTS로 안전.
-- baseline-on-migrate=true 설정으로 기존 DB에서는 이 파일이 실행되지 않음.

-- ============================================================
-- chat_rooms (ChatRoom 엔티티)
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_rooms (
    id                  VARCHAR(50) PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    color               VARCHAR(7),
    external_id         VARCHAR(255) UNIQUE,
    room_type           VARCHAR(20),
    is_private          BOOLEAN DEFAULT FALSE,
    password            VARCHAR(255),
    allow_invites       BOOLEAN DEFAULT TRUE,
    allowed_roles       VARCHAR(200),
    participant_count   INTEGER DEFAULT 0,
    max_participants    INTEGER DEFAULT 10,
    created_by          VARCHAR(100),
    created_at          TIMESTAMP NOT NULL,
    last_message_at     TIMESTAMP,
    pinned_message_id   VARCHAR(36)
);

CREATE INDEX IF NOT EXISTS idx_chat_room_created_at ON chat_rooms(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_room_name ON chat_rooms(name);

-- ============================================================
-- chat_messages (ChatMessageEntity 엔티티)
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_messages (
    message_id              VARCHAR(36) PRIMARY KEY,
    chat_room_id            VARCHAR(50) NOT NULL,
    user_id                 VARCHAR(50),
    username                VARCHAR(100) NOT NULL,
    content                 TEXT NOT NULL,
    timestamp               TIMESTAMP NOT NULL,
    type                    VARCHAR(20),
    priority                VARCHAR(10),
    is_ai_generated         BOOLEAN DEFAULT FALSE,
    file_url                VARCHAR(512),
    file_name               VARCHAR(255),
    file_content_type       VARCHAR(100),
    parent_message_id       VARCHAR(36),
    parent_message_preview  VARCHAR(150),
    is_deleted              BOOLEAN DEFAULT FALSE,
    is_edited               BOOLEAN DEFAULT FALSE,
    edited_at               TIMESTAMP,
    pinned                  BOOLEAN DEFAULT FALSE,
    reactions               TEXT
);

CREATE INDEX IF NOT EXISTS idx_chat_room_id ON chat_messages(chat_room_id);
CREATE INDEX IF NOT EXISTS idx_timestamp ON chat_messages(timestamp);
CREATE INDEX IF NOT EXISTS idx_chat_room_timestamp ON chat_messages(chat_room_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_parent_message_id ON chat_messages(parent_message_id);

-- ============================================================
-- outbox_events (OutboxEvent 엔티티)
-- ============================================================
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(50) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    partition_key   VARCHAR(100) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    processed_at    TIMESTAMP,
    version         BIGINT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events(status, created_at);
