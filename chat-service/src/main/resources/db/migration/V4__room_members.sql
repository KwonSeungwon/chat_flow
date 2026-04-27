-- 채팅방 멤버십 명시 추적 테이블.
-- DM 만석 재입장 판정에 사용 (메시지 이력 의존 제거).
CREATE TABLE IF NOT EXISTS room_members (
    room_id    VARCHAR(50) NOT NULL,
    user_id    VARCHAR(36) NOT NULL,
    username   VARCHAR(50) NOT NULL,
    joined_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_room_members_room
        FOREIGN KEY (room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_room_members_user_id ON room_members(user_id);

-- 기존 데이터 backfill: chat_messages에 메시지 발신 이력이 있는
-- (chat_room_id, user_id) 조합을 멤버십으로 등록.
-- joined_at은 첫 발신 시각으로, username은 마지막으로 알려진 값을 사용.
INSERT INTO room_members (room_id, user_id, username, joined_at)
SELECT m.chat_room_id,
       m.user_id,
       (SELECT username FROM chat_messages
        WHERE chat_room_id = m.chat_room_id AND user_id = m.user_id
        ORDER BY timestamp DESC LIMIT 1) AS username,
       MIN(m.timestamp) AS joined_at
FROM chat_messages m
WHERE m.user_id IS NOT NULL AND m.user_id <> ''
GROUP BY m.chat_room_id, m.user_id
ON CONFLICT (room_id, user_id) DO NOTHING;
