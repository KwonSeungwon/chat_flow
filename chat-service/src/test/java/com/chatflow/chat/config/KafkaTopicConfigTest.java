package com.chatflow.chat.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class KafkaTopicConfigTest {

    @ParameterizedTest
    @ValueSource(shorts = {1, 3})
    void allTopics_shouldUseConfiguredReplicationFactor(short replication) {
        KafkaTopicConfig config = new KafkaTopicConfig();
        ReflectionTestUtils.setField(config, "replicationFactor", replication);

        NewTopic chatMessages = config.chatMessagesTopic();
        NewTopic aiSummaryRequests = config.aiSummaryRequestsTopic();
        NewTopic aiSummaries = config.aiSummariesTopic();

        assertAll(
                () -> assertEquals(replication, chatMessages.replicationFactor(),
                        "chatMessages replication"),
                () -> assertEquals(replication, aiSummaryRequests.replicationFactor(),
                        "aiSummaryRequests replication"),
                () -> assertEquals(replication, aiSummaries.replicationFactor(),
                        "aiSummaries replication")
        );
    }

    @Test
    void allTopics_shouldHaveThreePartitions() {
        KafkaTopicConfig config = new KafkaTopicConfig();
        ReflectionTestUtils.setField(config, "replicationFactor", (short) 1);

        NewTopic chatMessages = config.chatMessagesTopic();
        NewTopic aiSummaryRequests = config.aiSummaryRequestsTopic();
        NewTopic aiSummaries = config.aiSummariesTopic();

        assertAll(
                () -> assertEquals(3, chatMessages.numPartitions(),
                        "chatMessages partitions"),
                () -> assertEquals(3, aiSummaryRequests.numPartitions(),
                        "aiSummaryRequests partitions"),
                () -> assertEquals(3, aiSummaries.numPartitions(),
                        "aiSummaries partitions")
        );
    }
}
