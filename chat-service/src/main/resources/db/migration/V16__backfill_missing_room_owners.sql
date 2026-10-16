-- V16: 생성자 레거시 브리지 제거 전 안전망 — OWNER 없는 방의 생성자 OWNER 행 보강.
--
-- 배경: RoomMembershipChecker / RoomMembershipGuard가 chat_rooms.created_by를
-- 멤버십으로 인정하던 레거시 브리지(+ OWNER 자동 backfill)를 제거한다. 브리지는
-- 강퇴/밴/퇴장한 생성자가 스스로 OWNER를 재획득하는 구멍(S-8)이었다.
-- 브리지가 사라지면 room_members 행이 없는 생성자는 자기 방에 접근할 수 없으므로,
-- 제거 전에 "OWNER가 한 명도 없고, 생성자 행도 없는 방"에만 생성자 OWNER 행을 넣는다.
--
-- V10과의 차이: V10은 생성자 행이 이미 있으면 ON CONFLICT로 OWNER 승격까지 했다.
-- V16은 순수 INSERT-only — 생성자 행이 존재하면(강퇴 후 재입장한 MEMBER 등)
-- 절대 건드리지 않는다.
--
-- 불변식: 이미 OWNER가 있는 방은 건드리지 않는다 (transferOwnership은 created_by를
-- 갱신하지 않으므로 created_by 기준 승격은 이전된 소유권을 되돌린다).
--
-- 알려진 한계: 밴된 생성자의 행은 banUser가 삭제하므로, OWNER 없는 방에서 밴된
-- 생성자가 있으면 여기서 복권될 수 있다 → room_bans에 있는 생성자는 제외한다.
--
-- 멱등성: WHERE NOT EXISTS + ON CONFLICT DO NOTHING — 재실행은 no-op.

INSERT INTO room_members (room_id, user_id, username, joined_at, role)
SELECT cr.id,
       cr.created_by,
       -- room_members.username은 VARCHAR(50) — 표시명만 절단 (identity는 user_id).
       COALESCE(LEFT(u.username, 50), cr.created_by),
       COALESCE(cr.created_at, NOW()),
       'OWNER'
FROM chat_rooms cr
LEFT JOIN users u ON u.user_id = cr.created_by
WHERE cr.created_by IS NOT NULL
  -- room_members.user_id는 VARCHAR(36): 규격 외 행은 절단 없이 스킵 (V10과 동일 가드).
  AND char_length(cr.created_by) <= 36
  AND NOT EXISTS (
      SELECT 1 FROM room_members rm
      WHERE rm.room_id = cr.id
        AND rm.user_id = cr.created_by
  )
  AND NOT EXISTS (
      SELECT 1 FROM room_members rm
      WHERE rm.room_id = cr.id
        AND rm.role = 'OWNER'
  )
  AND NOT EXISTS (
      SELECT 1 FROM room_bans rb
      WHERE rb.room_id = cr.id
        AND rb.user_id = cr.created_by
  )
ON CONFLICT (room_id, user_id) DO NOTHING;
