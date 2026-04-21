package com.chatflow.chat.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 핵심 Kafka 토픽의 파티션 수를 명시적으로 선언.
 * Spring Kafka KafkaAdmin이 기동 시 자동으로 토픽을 생성/증설한다.
 * 파티션은 증가만 가능하며, chatRoomId 파티션 키로 룸 단위 순서가 보장된다.
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    // 3-broker StatefulSet 전환으로 replication 1→3
    private static final short REPLICATION = 3;

    @Bean
    public NewTopic chatMessagesTopic() {
        return TopicBuilder.name("chat-messages")
                .partitions(PARTITIONS)
                .replicas(REPLICATION)
                .build();
    }

    @Bean
    public NewTopic aiSummaryRequestsTopic() {
        return TopicBuilder.name("ai-summary-requests")
                .partitions(PARTITIONS)
                .replicas(REPLICATION)
                .build();
    }

    @Bean
    public NewTopic aiSummariesTopic() {
        return TopicBuilder.name("ai-summaries")
                .partitions(PARTITIONS)
                .replicas(REPLICATION)
                .build();
    }
}
