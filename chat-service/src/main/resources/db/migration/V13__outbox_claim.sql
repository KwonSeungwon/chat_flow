ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS claim_token VARCHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS claimed_at  TIMESTAMP   NULL;

-- reaper scan (stale PROCESSING) + claim churn
CREATE INDEX IF NOT EXISTS idx_outbox_claimed_at ON outbox_events (claimed_at);
