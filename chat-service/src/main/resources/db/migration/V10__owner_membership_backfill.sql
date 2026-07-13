-- V10: 레거시 방의 생성자 OWNER 멤버십 백필.
--
-- 배경: deleteRoom/updateRoomSettings 권한이 createdBy 비교에서 role 기반
-- (requireRole(OWNER))으로 전환됨. createRoom은 생성 시점에 생성자를 OWNER로
-- seed하지만, 그 이전에 만들어진 방 중 생성자가 room_members에 행이 아예 없는
-- 경우(발화 이력 없음 → V4 백필 누락, DM 아님 → V5 백필 누락)는 생성자가
-- 자기 방을 삭제/설정 변경할 수 없게 잠긴다(403).
--
-- V6 step 2는 "이미 존재하는" 생성자 행만 UPDATE로 OWNER 승격했으므로,
-- 행 자체가 없는 생성자는 여전히 누락 상태다. 이 마이그레이션이 그 갭을 닫는다.
--
-- 불변식(반드시 유지): 이미 OWNER 행이 있는 방은 절대 건드리지 않는다.
-- transferOwnership은 role 행만 교체하고 created_by는 갱신하지 않으므로,
-- created_by 기준으로 무조건 승격하면 이전(transfer)된 소유권을 되돌려버린다.
-- 따라서 "OWNER가 한 명도 없는 방"으로 한정한다.
--
-- 의도된 부수효과: OWNER가 leaveRoom으로 나가 방이 무주공산이 된 경우
-- (이전받은 OWNER가 떠난 방 포함) 생성자가 OWNER로 복권된다 — requireRole(OWNER)
-- 체제에서 OWNER 없는 방은 영구히 삭제 불가이므로 복권이 올바른 동작이다.
--
-- username은 gateway의 users 테이블에서 조회(V5와 같은 교차 의존 — 단 V5는
-- username, 여기는 user_id로 조인), 없으면 created_by 값으로 폴백
-- (RoomMembershipService.addMemberIfAbsent와 동일한 폴백 규칙).
--
-- 멱등성: 1회 실행으로 대상 방에 OWNER가 생기므로 재실행은 no-op.
-- 롤링 배포 중 구 파드가 만든 방이 스냅샷에서 빠질 수 있으나, 멱등이므로
-- 필요 시 psql로 동일 문장을 1회 재실행하면 닫힌다.

INSERT INTO room_members (room_id, user_id, username, joined_at, role)
SELECT cr.id,
       cr.created_by,
       -- users.username은 VARCHAR(100)이고 가입 시 길이 검증이 없어 50자 초과가
       -- 가능. room_members.username은 VARCHAR(50)이므로 표시명만 절단한다
       -- (identity는 user_id — 절단해도 안전).
       COALESCE(LEFT(u.username, 50), cr.created_by),
       COALESCE(cr.created_at, NOW()),
       'OWNER'
FROM chat_rooms cr
LEFT JOIN users u ON u.user_id = cr.created_by
WHERE cr.created_by IS NOT NULL
  -- 방어 가드: room_members.user_id는 VARCHAR(36). created_by는 항상 UUID(36자)지만,
  -- 규격 외 행이 하나라도 있으면 마이그레이션 전체가 실패해 배포가 막히므로
  -- 해당 행은 절단하지 않고 스킵한다 (identity 컬럼 절단 금지).
  AND char_length(cr.created_by) <= 36
  AND NOT EXISTS (
      SELECT 1 FROM room_members rm
      WHERE rm.room_id = cr.id
        AND rm.role = 'OWNER'
  )
ON CONFLICT (room_id, user_id)
    DO UPDATE SET role = 'OWNER';
