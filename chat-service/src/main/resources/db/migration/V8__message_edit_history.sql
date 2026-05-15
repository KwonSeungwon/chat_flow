-- Message edit history — every time a user edits a message, the OLD content
-- (pre-edit) is captured here. The chat_messages.content column always holds
-- the latest version; this table is the audit trail.
--
-- We deliberately do NOT cascade-delete with chat_messages: if a message is
-- soft-deleted later, the history remains for moderation/audit. A hard
-- delete (rare) would orphan history rows; that is acceptable for an audit
-- table (history without a target is still queryable for compliance).
CREATE TABLE IF NOT EXISTS message_edit_history (
    id              BIGSERIAL    PRIMARY KEY,
    message_id      VARCHAR(36)  NOT NULL,
    previous_content TEXT        NOT NULL,
    edited_at       TIMESTAMP    NOT NULL,
    edited_by       VARCHAR(50)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meh_message_id_edited_at
    ON message_edit_history (message_id, edited_at DESC);
