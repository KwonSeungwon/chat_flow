-- 기존 운영 DB에는 users.role(NOT NULL DEFAULT 'NURSE') 컬럼이 남아 있다. 엔티티가 매핑하지 않으므로
-- R2DBC INSERT 컬럼 목록에서 빠지고 DB 기본값이 채워 INSERT가 계속 성공한다. 컬럼 DROP은 후속 릴리스.
CREATE TABLE IF NOT EXISTS users (
    seq BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL UNIQUE,
    username VARCHAR(100) NOT NULL UNIQUE,
    encoded_password VARCHAR(255) NOT NULL,
    profile_image_url VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_user_id ON users(user_id);

-- Phase 2A: profile 확장 (status message + bio)
ALTER TABLE users ADD COLUMN IF NOT EXISTS status_message VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(300);
