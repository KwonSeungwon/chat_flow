-- V13이 도입한 claim 패턴(PENDING -> PROCESSING)이 prod에서 100% 실패하던 것을 고친다.
--
-- prod 스키마는 Flyway baseline 이전에 Hibernate ddl-auto가 만든 것이라,
-- OutboxStatus 이넘이 {PENDING, PROCESSED, FAILED} 3값이던 시절의 CHECK 제약
-- (outbox_events_status_check)이 그대로 남아 있었다. 이넘에 PROCESSING을 추가해도
-- 기존 DB 제약은 갱신되지 않으므로 OutboxPoller.claimBatch()가 매 폴링(200ms)마다
-- SQLState 23514로 터졌고, 2026-07-21 이후 outbox 이벤트가 전부 PENDING에 묶였다.
-- (= Kafka 미발행 → Elasticsearch 색인/AI 요약/감사 이벤트 전부 정지)
--
-- baseline(V1)으로 생성된 DB에는 이 제약이 없다. IF EXISTS로 양쪽을 흡수하고
-- 모든 환경이 동일한 4값 제약을 갖도록 다시 만든다.
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS outbox_events_status_check;

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED'));
