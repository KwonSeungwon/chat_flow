CREATE TABLE game_player (
    user_id    VARCHAR(64)              PRIMARY KEY,
    avatar     VARCHAR(8)               NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
