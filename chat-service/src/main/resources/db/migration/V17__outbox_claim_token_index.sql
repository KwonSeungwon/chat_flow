-- OutboxPoller.doPoll() reads back its claimed batch with
-- findByClaimTokenAndStatus(token, PROCESSING) every 200ms. Without an index on
-- claim_token that is a sequential scan of outbox_events per poll (B-13).
CREATE INDEX IF NOT EXISTS idx_outbox_claim_token ON outbox_events (claim_token);
