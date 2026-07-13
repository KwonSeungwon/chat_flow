-- V11: 구조화된 멘션 테이블.
-- 기존 findMentionsOf는 content LIKE '%@user%' 전체 스캔이었고,
-- (a) 암호화 활성 시 ciphertext를 매칭해 아무것도 못 찾는 정합성 버그,
-- (b) 방 멤버십 미검증 정보 누출, (c) leading-wildcard 성능 문제가 있었다.
-- 이제 멘션은 발신 시점에 방 멤버로 한정해 행으로 기록된다.
CREATE TABLE IF NOT EXISTS message_mentions (
    id                 BIGSERIAL PRIMARY KEY,
    message_id         VARCHAR(36)  NOT NULL,
    room_id            VARCHAR(50)  NOT NULL,
    mentioned_user_id  VARCHAR(36)  NOT NULL,
    mentioned_username VARCHAR(50)  NOT NULL,
    from_username      VARCHAR(50)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    read               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_message_mentions UNIQUE (message_id, mentioned_user_id),
    CONSTRAINT fk_message_mentions_room FOREIGN KEY (room_id)
        REFERENCES chat_rooms(id) ON DELETE CASCADE
);

-- 디이제스트 조회(list/unread-count)는 항상 mentioned_user_id + created_at 기준.
CREATE INDEX IF NOT EXISTS idx_message_mentions_user_created
    ON message_mentions (mentioned_user_id, created_at DESC);
-- 메시지 삭제 전파 시 message_id 로 제거.
CREATE INDEX IF NOT EXISTS idx_message_mentions_message
    ON message_mentions (message_id);

-- 평문 히스토리 백필 (최근 365일). 암호화가 켜져 있던 기간의 content는
-- ciphertext라 매칭되지 않음(무해한 no-op). read=true로 넣어 배지 폭주를 막는다
-- (멘션 화면에는 계속 보인다). 자기 멘션 제외, 삭제/AI 메시지 제외.
-- NOTE: chat_messages.username is VARCHAR(100), from_username is VARCHAR(50) → LEFT guard.
-- NOTE: Entity maps 'deleted' field to column 'is_deleted' (V1 baseline).
INSERT INTO message_mentions
    (message_id, room_id, mentioned_user_id, mentioned_username, from_username, created_at, read)
SELECT m.message_id,
       m.chat_room_id,
       rm.user_id,
       rm.username,
       LEFT(m.username, 50),
       m.timestamp,
       TRUE
FROM chat_messages m
JOIN room_members rm
  ON rm.room_id = m.chat_room_id
 AND m.content LIKE '%@' || rm.username || '%'
 AND rm.username <> m.username
WHERE m.type = 'CHAT'
  AND m.is_deleted = FALSE
  AND m.is_ai_generated = FALSE
  AND m.timestamp >= NOW() - INTERVAL '365 days'
ON CONFLICT (message_id, mentioned_user_id) DO NOTHING;
