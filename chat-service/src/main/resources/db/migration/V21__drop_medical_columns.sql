-- V21: 병원 컬럼 DROP(users.role, chat_rooms.allowed_roles, chat_messages.priority).
--
-- 운영 파드는 모두 세 컬럼을 매핑하지 않는 이미지라 지워도 된다. IF EXISTS로 다시 돌려도 안전하고,
-- gateway schema.sql로 만든 새 DB(users.role 없음)에서도 성공한다.
--
-- DROP 전에 V20 정규화(1번, 3·4번)를 한 번 더 돌린다. V20 이후 옛 클라이언트가 HANDOFF 방이나
-- PATIENT_CARD·SBAR 메시지를 다시 만들었을 수 있다. V20의 2번(allowed_roles NULL)은 컬럼을 지우므로 뺀다.
-- 삭제 대상 설명은 V20 주석을 따른다.
--
-- 마지막 ALTER가 세 테이블에 ACCESS EXCLUSIVE 락을 잡는다. 긴 트랜잭션 뒤에서 기다리며 채팅·로그인을
-- 막지 않도록 5초 안에 락을 못 잡으면 실패시킨다(트랜잭션 롤백, 파드 재시작 때 다시 시도).
SET LOCAL lock_timeout = '5s';

-- 1) 인수인계방 → 일반방. 인수인계 기본색(청록)은 일반방 기본색으로 바꾼다.
UPDATE chat_rooms
SET room_type = 'GENERAL',
    color = CASE WHEN UPPER(color) = '#00796B' THEN '#6366f1' ELSE color END
WHERE room_type = 'HANDOFF';

-- 3) 대상 메시지를 가리키는 행 정리.
DELETE FROM message_mentions
WHERE message_id IN (
    SELECT message_id FROM chat_messages
    WHERE type = 'PATIENT_CARD' OR (type = 'CHAT' AND content LIKE '{"type":"SBAR"%'));

DELETE FROM message_reports
WHERE message_id IN (
    SELECT message_id FROM chat_messages
    WHERE type = 'PATIENT_CARD' OR (type = 'CHAT' AND content LIKE '{"type":"SBAR"%'));

DELETE FROM message_edit_history
WHERE message_id IN (
    SELECT message_id FROM chat_messages
    WHERE type = 'PATIENT_CARD' OR (type = 'CHAT' AND content LIKE '{"type":"SBAR"%'));

-- SBAR로 보냈다가 일반 텍스트로 고친 메시지는 남지만, 편집 이력에 카드 원문이 있다.
-- 그 이력 행만 지운다. 같은 메시지의 일반 텍스트 이력은 남긴다.
DELETE FROM message_edit_history WHERE previous_content LIKE '{"type":"SBAR"%';

UPDATE chat_rooms SET pinned_message_id = NULL
WHERE pinned_message_id IN (
    SELECT message_id FROM chat_messages
    WHERE type = 'PATIENT_CARD' OR (type = 'CHAT' AND content LIKE '{"type":"SBAR"%'));

-- 카드에 단 답장은 남기고 일반 메시지로 만든다. 미리보기에 카드 원문 JSON이 들어 있다.
UPDATE chat_messages SET parent_message_id = NULL, parent_message_preview = NULL
WHERE parent_message_id IN (
    SELECT message_id FROM chat_messages
    WHERE type = 'PATIENT_CARD' OR (type = 'CHAT' AND content LIKE '{"type":"SBAR"%'));

-- 4) 메시지 삭제.
DELETE FROM chat_messages
WHERE type = 'PATIENT_CARD' OR (type = 'CHAT' AND content LIKE '{"type":"SBAR"%');

-- 5) 컬럼 DROP.
ALTER TABLE IF EXISTS users DROP COLUMN IF EXISTS role;
ALTER TABLE IF EXISTS chat_rooms DROP COLUMN IF EXISTS allowed_roles;
ALTER TABLE IF EXISTS chat_messages DROP COLUMN IF EXISTS priority;
