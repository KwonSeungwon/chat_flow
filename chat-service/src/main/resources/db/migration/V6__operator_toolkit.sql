-- 1) room_members에 역할/뮤트 컬럼 추가
ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN IF NOT EXISTS muted_until TIMESTAMP NULL;

-- 2) 기존 chat_rooms.created_by 기반 OWNER 백필
UPDATE room_members rm
SET role = 'OWNER'
FROM chat_rooms cr
WHERE rm.room_id = cr.id
  AND rm.user_id = cr.created_by;

-- 3) ban 테이블
CREATE TABLE IF NOT EXISTS room_bans (
    room_id    VARCHAR(50) NOT NULL,
    user_id    VARCHAR(36) NOT NULL,
    banned_by  VARCHAR(36) NOT NULL,
    reason     VARCHAR(255),
    banned_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_room_bans_room FOREIGN KEY (room_id)
        REFERENCES chat_rooms(id) ON DELETE CASCADE
);

-- 4) 메시지 신고 테이블
CREATE TABLE IF NOT EXISTS message_reports (
    id           BIGSERIAL PRIMARY KEY,
    message_id   VARCHAR(36) NOT NULL,
    room_id      VARCHAR(50) NOT NULL,
    reported_by  VARCHAR(36) NOT NULL,
    reason       VARCHAR(50) NOT NULL,
    comment      VARCHAR(500),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolved_by  VARCHAR(36),
    resolved_at  TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_message_reports_room FOREIGN KEY (room_id)
        REFERENCES chat_rooms(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_message_reports_room_status
    ON message_reports(room_id, status);
CREATE INDEX IF NOT EXISTS idx_message_reports_message
    ON message_reports(message_id);
