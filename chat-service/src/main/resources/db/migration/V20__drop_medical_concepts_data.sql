-- V20: 병원 컨셉 제거(Gossiplog 리브랜딩) 데이터 정규화.
--
-- 컬럼 chat_rooms.allowed_roles, chat_messages.priority는 지우지 않는다. 롤링 업데이트 중
-- 옛 파드가 아직 매핑하고 있다. DROP은 후속 릴리스에서 한다.
--
-- 삭제 대상은 PATIENT_CARD 메시지와 SBAR 카드(type CHAT, content가 {"type":"SBAR" 로 시작)다.
-- content는 CHATFLOW_ENCRYPTION_KEY가 켜져 있으면 AES-GCM Base64로 저장된다. Base64는 '{'로
-- 시작하지 않으므로 암호화된 SBAR 행은 LIKE에 걸리지 않고 남는다(오삭제 없음). 평문 행만 지운다.
--
-- chat_messages를 참조하는 FK는 없다(message_id는 모두 논리 참조). 그래서 참조 행을 먼저
-- 직접 정리한 뒤 메시지를 지운다. 대상 집합은 마지막 DELETE 전까지 그대로라 같은 서브쿼리를 쓴다.

-- 1) 인수인계방 → 일반방. 인수인계 기본색(청록)은 일반방 기본색으로 바꾼다.
UPDATE chat_rooms
SET room_type = 'GENERAL',
    color = CASE WHEN UPPER(color) = '#00796B' THEN '#6366f1' ELSE color END
WHERE room_type = 'HANDOFF';

-- 2) 방 허용 역할은 더 쓰지 않는다.
UPDATE chat_rooms SET allowed_roles = NULL WHERE allowed_roles IS NOT NULL;

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
