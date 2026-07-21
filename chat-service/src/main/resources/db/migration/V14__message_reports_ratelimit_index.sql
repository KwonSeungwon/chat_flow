-- Supporting index for the per-user report rate-limit query
-- (MessageReportRepository.countByReportedByAndCreatedAtAfter → WHERE reported_by = ? AND created_at > ?).
CREATE INDEX IF NOT EXISTS idx_message_reports_reporter_created
    ON message_reports (reported_by, created_at);
