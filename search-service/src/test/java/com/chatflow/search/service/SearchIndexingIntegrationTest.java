package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.chatflow.search.util.SearchConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

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
        service = new SearchService(client, mapper);
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
    void batch_indexesDocs_thenDeleteRemovesOne_andSameBatchDeleteWins() throws Exception {
        service.indexChatMessages(List.of(msg("it-1", false), msg("it-2", false)));
        assertThat(count()).isEqualTo(2L);

        service.indexChatMessages(List.of(msg("it-1", true)));  // delete in its own batch
        assertThat(count()).isEqualTo(1L);

        // index + delete of the same id in one batch must not resurrect the document
        service.indexChatMessages(List.of(msg("it-3", false), msg("it-3", true)));
        assertThat(count()).isEqualTo(1L);
    }
}
