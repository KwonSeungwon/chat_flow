-- V19: Wiki Dungeon game tables (com.chatflow.chat.game).
-- Questions are uploaded by the local wiki-quiz-gen skill through the admin bulk API
-- as DRAFT and only APPROVED questions are served. choices is a JSON array stored as
-- TEXT (no jsonb precedent; prod runs ddl-auto=validate).

CREATE TABLE IF NOT EXISTS game_dungeon (
    id             VARCHAR(30)  PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    sort_order     INT          NOT NULL,
    building_style VARCHAR(30)  NOT NULL
);

CREATE TABLE IF NOT EXISTS game_topic (
    id         BIGSERIAL    PRIMARY KEY,
    dungeon_id VARCHAR(30)  NOT NULL REFERENCES game_dungeon(id),
    title      VARCHAR(200) NOT NULL,
    summary    TEXT,
    source_ref VARCHAR(500),
    CONSTRAINT uq_game_topic_dungeon_title UNIQUE (dungeon_id, title)
);

CREATE TABLE IF NOT EXISTS game_monster (
    id         BIGSERIAL    PRIMARY KEY,
    topic_id   BIGINT       REFERENCES game_topic(id) ON DELETE CASCADE,
    dungeon_id VARCHAR(30)  NOT NULL REFERENCES game_dungeon(id),
    name       VARCHAR(100) NOT NULL,
    sprite_key VARCHAR(100) NOT NULL,
    is_boss    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- One monster per topic, one boss per dungeon.
CREATE UNIQUE INDEX IF NOT EXISTS uq_game_monster_topic
    ON game_monster (topic_id) WHERE topic_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_game_monster_boss
    ON game_monster (dungeon_id) WHERE is_boss;

CREATE TABLE IF NOT EXISTS game_question (
    id           BIGSERIAL   PRIMARY KEY,
    topic_id     BIGINT      NOT NULL REFERENCES game_topic(id) ON DELETE CASCADE,
    stem         TEXT        NOT NULL,
    choices      TEXT        NOT NULL,
    answer_index SMALLINT    NOT NULL CHECK (answer_index BETWEEN 0 AND 3),
    explanation  TEXT        NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT game_question_status_check CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    content_hash CHAR(64)    NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    reviewed_at  TIMESTAMP,
    CONSTRAINT uq_game_question_content_hash UNIQUE (content_hash)
);

CREATE INDEX IF NOT EXISTS idx_game_question_topic_status
    ON game_question (topic_id, status);
CREATE INDEX IF NOT EXISTS idx_game_question_status_id
    ON game_question (status, id);

CREATE TABLE IF NOT EXISTS game_progress (
    user_id     VARCHAR(50)  NOT NULL,
    dungeon_id  VARCHAR(30)  NOT NULL REFERENCES game_dungeon(id),
    username    VARCHAR(100),
    cleared_at  TIMESTAMP,
    best_streak INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, dungeon_id)
);

CREATE TABLE IF NOT EXISTS game_answer (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     VARCHAR(50) NOT NULL,
    question_id BIGINT      NOT NULL REFERENCES game_question(id) ON DELETE CASCADE,
    correct     BOOLEAN     NOT NULL,
    answered_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    next_due_at TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_game_answer_user_due
    ON game_answer (user_id, next_due_at);

-- Seed: 8 buildings (building_style = id) and one boss per building.
INSERT INTO game_dungeon (id, name, sort_order, building_style) VALUES
    ('k8s',   '쿠버네티스 성', 1, 'k8s'),
    ('jvm',   'JVM 대장간',    2, 'jvm'),
    ('db',    'DB 도서관',     3, 'db'),
    ('net',   '네트워크 요새', 4, 'net'),
    ('build', '빌드 공방',     5, 'build'),
    ('ops',   '운영 관제탑',   6, 'ops'),
    ('ai',    'AI 연구소',     7, 'ai'),
    ('shell', '셸 동굴',       8, 'shell')
ON CONFLICT (id) DO NOTHING;

INSERT INTO game_monster (topic_id, dungeon_id, name, sprite_key, is_boss) VALUES
    (NULL, 'k8s',   '크래시루프 드래곤',    'boss_k8s',   TRUE),
    (NULL, 'jvm',   '가비지 골렘',          'boss_jvm',   TRUE),
    (NULL, 'db',    '데드락 리치',          'boss_db',    TRUE),
    (NULL, 'net',   '타임아웃 와이번',      'boss_net',   TRUE),
    (NULL, 'build', '머지 컨플릭트 키메라', 'boss_build', TRUE),
    (NULL, 'ops',   '알람 폭풍 정령',       'boss_ops',   TRUE),
    (NULL, 'ai',    '할루시네이션 미믹',    'boss_ai',    TRUE),
    (NULL, 'shell', '좀비 프로세스',        'boss_shell', TRUE)
ON CONFLICT DO NOTHING;
