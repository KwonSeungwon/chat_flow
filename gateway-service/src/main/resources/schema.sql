CREATE TABLE IF NOT EXISTS users (
    seq BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL UNIQUE,
    username VARCHAR(100) NOT NULL UNIQUE,
    encoded_password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'NURSE',
    profile_image_url VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_user_id ON users(user_id);

-- Phase 2A: profile 확장 (status message + bio)
ALTER TABLE users ADD COLUMN IF NOT EXISTS status_message VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(300);
