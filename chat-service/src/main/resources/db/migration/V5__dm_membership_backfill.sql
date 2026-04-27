-- V4의 chat_messages 기반 백필은 발화 이력이 있는 멤버만 등록함.
-- DM 방에 입장만 하고 메시지를 보내지 않은 멤버는 누락되므로,
-- chat_rooms.name = 'DM:user1,user2' 형식을 파싱해 양 구성원을 보강 등록.
INSERT INTO room_members (room_id, user_id, username, joined_at)
SELECT cr.id,
       u.user_id,
       u.username,
       COALESCE(cr.created_at, NOW())
FROM chat_rooms cr
CROSS JOIN LATERAL unnest(string_to_array(substring(cr.name FROM 4), ',')) AS dm_username
JOIN users u ON u.username = dm_username
WHERE cr.room_type = 'DIRECT'
  AND cr.name LIKE 'DM:%'
ON CONFLICT (room_id, user_id) DO NOTHING;
