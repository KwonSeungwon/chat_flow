-- users.role 컬럼은 chat-service V21(drop_medical_columns)이 지운다. 엔티티는 role을 매핑하지 않는다.
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

-- 소셜 로그인 계정 연결. 제공자 계정 하나는 한 사용자에게만, 한 사용자는 제공자마다 하나만 연결한다.
CREATE TABLE IF NOT EXISTS user_social_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMP,
    CONSTRAINT uq_social_provider_account UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_social_user_provider UNIQUE (user_id, provider)
);
