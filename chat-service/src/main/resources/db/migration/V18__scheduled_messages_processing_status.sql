-- Per-row scheduled delivery claims a due row with
--   UPDATE scheduled_messages SET status = 'PROCESSING' WHERE id = ? AND status = 'PENDING'
-- inside the row's own delivery transaction (B-7).
--
-- Same drift hazard as V15 (outbox): if any environment carries a Hibernate-generated
-- CHECK constraint listing only the original four statuses, the claim would fail with
-- SQLState 23514 on every poll. Drop it if present and recreate it with all five values
-- so every environment ends up with the same constraint.
ALTER TABLE scheduled_messages DROP CONSTRAINT IF EXISTS scheduled_messages_status_check;

ALTER TABLE scheduled_messages
    ADD CONSTRAINT scheduled_messages_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'CANCELED', 'FAILED'));
