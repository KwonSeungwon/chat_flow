package com.chatflow.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.chatflow.search.util.SearchConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.StringReader;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IndexInitializer {

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndices() {
        createIfMissing(SearchConstants.CHAT_MESSAGES_INDEX, "elasticsearch/korean-analyzer-config.json");
        createIfMissing("audit_logs", "elasticsearch/audit-logs-config.json");
    }

    /**
     * Creates {@code indexName} from the settings/mappings in the classpath JSON
     * {@code resource} unless it already exists. Failures are logged, not thrown,
     * so the service still starts when ES is temporarily unavailable.
     */
    void createIfMissing(String indexName, String resource) {
        try {
            boolean exists = elasticsearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();
            if (exists) {
                log.info("{} index already exists", indexName);
                return;
            }

            log.info("Creating {} index from {}", indexName, resource);
            try (InputStream inputStream = new ClassPathResource(resource).getInputStream()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> indexConfig = objectMapper.readValue(inputStream, Map.class);

                String settingsJson = objectMapper.writeValueAsString(indexConfig.get("settings"));
                String mappingsJson = objectMapper.writeValueAsString(indexConfig.get("mappings"));

                CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                        .index(indexName)
                        .settings(s -> s.withJson(new StringReader(settingsJson)))
                        .mappings(m -> m.withJson(new StringReader(mappingsJson)))
                );
                elasticsearchClient.indices().create(createRequest);
                log.info("Successfully created {} index", indexName);
            }
        } catch (Exception e) {
            log.error("Failed to create {} index", indexName, e);
        }
    }
}
