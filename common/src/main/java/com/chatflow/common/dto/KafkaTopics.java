package com.chatflow.common.dto;

/**
 * Kafka 토픽 이름 상수. 모든 서비스에서 이 값을 참조해야 한다.
 * public static final String은 컴파일 상수이므로 @KafkaListener의 topics 속성에도 직접 사용 가능.
 */
public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String CHAT_MESSAGES = "chat-messages";
    public static final String AI_SUMMARY_REQUESTS = "ai-summary-requests";
    public static final String AI_SUMMARIES = "ai-summaries";
}
