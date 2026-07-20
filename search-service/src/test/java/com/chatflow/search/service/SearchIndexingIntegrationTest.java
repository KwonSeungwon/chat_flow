package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.chatflow.search.util.SearchConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SearchIndexingIntegrationTest {

    @Container
    static final ElasticsearchContainer ES = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.11.3")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    static ElasticsearchClient client;
    static RestClient restClient;
    static SearchService service;

    @BeforeAll
    static void setUp() throws Exception {
        restClient = RestClient.builder(HttpHost.create(ES.getHttpHostAddress())).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(mapper)));
        // Minimal index so mapping is deterministic (standard analyzer; Nori not needed here).
        client.indices().create(c -> c.index(SearchConstants.CHAT_MESSAGES_INDEX)
                .mappings(m -> m
                        .properties("chatRoomId", p -> p.keyword(k -> k))
                        .properties("content", p -> p.text(t -> t))
                        .properties("messageType", p -> p.keyword(k -> k))
                        .properties("timestamp", p -> p.date(d -> d))));
        service = new SearchService(client, mapper, new SimpleMeterRegistry());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (restClient != null) restClient.close();
    }

    private static String msg(String id, boolean deleted) {
        return """
            {"messageId":"%s","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"hello world","type":"CHAT","timestamp":"2026-07-04T12:00:00",
             "isDeleted":%s,"isAiGenerated":false}""".formatted(id, deleted);
    }

    private static long count() throws Exception {
        client.indices().refresh(r -> r.index(SearchConstants.CHAT_MESSAGES_INDEX));
        return client.count(c -> c.index(SearchConstants.CHAT_MESSAGES_INDEX)).count();
    }

    @Test
    void bufferFlush_indexesDocs_thenDeleteRemovesOne() throws Exception {
        service.indexChatMessage(msg("it-1", false));
        service.indexChatMessage(msg("it-2", false));
        service.scheduledFlush();               // triggers bulk index
        assertThat(count()).isEqualTo(2L);

        service.indexChatMessage(msg("it-1", true));  // delete path
        assertThat(count()).isEqualTo(1L);
    }
}
