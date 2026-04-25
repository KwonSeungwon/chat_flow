-- V2__dm_room_unique_name_index.sql
-- DM 방(room_type='DIRECT')에 대해 name 컬럼 partial unique index 추가.
-- canonical name 기반 중복 차단의 DB 레벨 방어.
--
-- 기존 데이터 정리 정책:
-- 동일 (room_type='DIRECT', name) 그룹 중 가장 오래된 방(MIN(created_at))만 남기고,
-- 중복 방의 메시지를 oldest 방으로 재할당, 중복 방을 삭제한다.

-- 1) 중복 그룹 식별 + oldest 방 선정 + 메시지 재할당
-- PostgreSQL/H2 호환을 위해 명시적 UPDATE/DELETE 사용 (CTE WITH UPDATE는 H2 제한)

-- 1-1) 임시 테이블에 oldest room id를 캐시 (중복 그룹별 한 row)
CREATE TEMPORARY TABLE dm_oldest AS
SELECT name, MIN(created_at) AS oldest_at
FROM chat_rooms
WHERE room_type = 'DIRECT'
GROUP BY name
HAVING COUNT(*) > 1;

CREATE TEMPORARY TABLE dm_keep AS
SELECT r.id AS keep_id, r.name
FROM chat_rooms r
JOIN dm_oldest o ON r.name = o.name AND r.created_at = o.oldest_at
WHERE r.room_type = 'DIRECT';

-- 1-2) 삭제 대상 방의 메시지를 keep 방으로 재할당
UPDATE chat_messages
SET chat_room_id = (
    SELECT k.keep_id FROM dm_keep k
    JOIN chat_rooms r2 ON r2.name = k.name AND r2.room_type = 'DIRECT'
    WHERE r2.id = chat_messages.chat_room_id AND r2.id <> k.keep_id
)
WHERE chat_room_id IN (
    SELECT r2.id FROM chat_rooms r2
    JOIN dm_keep k ON k.name = r2.name
    WHERE r2.room_type = 'DIRECT' AND r2.id <> k.keep_id
);

-- 1-3) 중복 방 삭제 (keep 제외)
DELETE FROM chat_rooms
WHERE room_type = 'DIRECT'
  AND id IN (
      SELECT r2.id FROM chat_rooms r2
      JOIN dm_keep k ON k.name = r2.name
      WHERE r2.room_type = 'DIRECT' AND r2.id <> k.keep_id
  );

DROP TABLE dm_keep;
DROP TABLE dm_oldest;

-- 2) Partial unique index — DIRECT 전용. 이후 동시 두 요청이 동일 canonical name으로
--    save 시 두 번째는 unique violation → DmRoomService.createOrFindDmRoom의 catch가
--    재조회하여 첫 번째 방을 반환.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_rooms_dm_name
    ON chat_rooms(name)
    WHERE room_type = 'DIRECT';
