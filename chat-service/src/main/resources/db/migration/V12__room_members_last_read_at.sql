-- V12: 내구성 있는 unread 커서. Redis chatflow:readat:* (24h TTL)은 만료 시
-- "2000년 이후 전체 카운트"로 조용히 퇴화하는 절벽이 있었다. 커서를
-- room_members 로 옮기면 만료가 없고, cutoff 비교가 SQL 안으로 들어와
-- 방마다 돌던 COUNT 를 단일 그룹 쿼리로 배치할 수 있다.
ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMP NULL;

-- 배포 기준선: 전원 all-read 로 시작 (배지 폭주 방지 — 멘션 백필과 동일 철학).
-- 이후 NULL 은 "가입 후 아직 읽지 않음" = joined_at 폴백 의미로만 쓰인다.
UPDATE room_members SET last_read_at = NOW() WHERE last_read_at IS NULL;
