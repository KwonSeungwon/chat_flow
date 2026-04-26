-- chat_messages에 forwarded_from 컬럼 추가 (전달 메시지 원본 발신자 메타)
ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS forwarded_from VARCHAR(200);
