-- V2__dm_room_unique_name_index.sql
-- DM 방(room_type='DIRECT')에 대해 name 컬럼 partial unique index 추가.
-- canonical name 기반 중복 차단의 DB 레벨 방어.
--
-- 기존 데이터 정리 정책:
-- 동일 (room_type='DIRECT', name) 그룹 중 (created_at ASC, id ASC) 사전순 최소 1개만 남기고,
-- 중복 방의 메시지를 keep 방으로 재할당, 중복 방을 삭제한다.
-- tie-breaker: created_at 동점 시 id 사전순 비교로 항상 1개만 선정.

-- 1) 중복 그룹의 keep 선정 — NOT EXISTS 패턴 (PostgreSQL/H2 모두 표준 지원)
--    같은 name 그룹에서 (created_at, id) 사전순 최소인 row 1개만 선택.
CREATE TEMPORARY TABLE dm_keep AS
SELECT r.name, r.id AS keep_id
FROM chat_rooms r
WHERE r.room_type = 'DIRECT'
  AND NOT EXISTS (
      SELECT 1 FROM chat_rooms r2
      WHERE r2.room_type = 'DIRECT'
        AND r2.name = r.name
        AND (r2.created_at < r.created_at
             OR (r2.created_at = r.created_at AND r2.id < r.id))
  )
  AND r.name IN (
      SELECT name FROM chat_rooms
      WHERE room_type = 'DIRECT'
      GROUP BY name HAVING COUNT(*) > 1
  );

-- 2) 삭제 대상 → keep 매핑 (delete_id : keep_id = N : 1, 각 delete_id에 대해 keep_id 1개)
--    자기참조 DELETE 회피를 위해 임시 테이블에 명시 분리.
CREATE TEMPORARY TABLE dm_remap AS
SELECT r.id AS delete_id, k.keep_id
FROM chat_rooms r
JOIN dm_keep k ON k.name = r.name
WHERE r.room_type = 'DIRECT' AND r.id <> k.keep_id;

-- 3) 메시지 재할당 (각 delete_id → 정확히 1개 keep_id, 스칼라 서브쿼리 단일 row 보장)
UPDATE chat_messages
SET chat_room_id = (SELECT keep_id FROM dm_remap WHERE delete_id = chat_messages.chat_room_id)
WHERE chat_room_id IN (SELECT delete_id FROM dm_remap);

-- 4) 중복 방 삭제
DELETE FROM chat_rooms WHERE id IN (SELECT delete_id FROM dm_remap);

DROP TABLE dm_remap;
DROP TABLE dm_keep;

-- 5) Partial unique index — DIRECT 전용. 이후 동시 두 요청이 동일 canonical name으로
--    save 시 두 번째는 unique violation → DmRoomService.createOrFindDmRoom의 catch가
--    재조회하여 첫 번째 방을 반환.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_rooms_dm_name
    ON chat_rooms(name)
    WHERE room_type = 'DIRECT';
