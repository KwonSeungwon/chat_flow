package com.chatflow.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
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
        createChatMessagesIndex();
        createBlogPostsIndex();
        createAuditLogsIndex();
    }

    private void createChatMessagesIndex() {
        try {
            String indexName = "chat_messages";

            // Check if index already exists
            boolean exists = elasticsearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();

            if (!exists) {
                log.info("Creating chat_messages index with Korean analyzer settings...");

                // Load index configuration from resources
                ClassPathResource resource = new ClassPathResource("elasticsearch/korean-analyzer-config.json");
                try (InputStream inputStream = resource.getInputStream()) {
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
                    log.info("Successfully created chat_messages index with Korean analyzer");
                }
            } else {
                log.info("chat_messages index already exists");
            }
        } catch (Exception e) {
            log.error("Failed to create chat_messages index", e);
        }
    }

    private void createBlogPostsIndex() {
        try {
            String indexName = "blog_posts";

            boolean exists = elasticsearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();

            if (!exists) {
                log.info("Creating blog_posts index with Korean analyzer settings...");

                ClassPathResource resource = new ClassPathResource("elasticsearch/blog-posts-config.json");
                try (InputStream inputStream = resource.getInputStream()) {
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
                    log.info("Successfully created blog_posts index with Korean analyzer");
                }
            } else {
                log.info("blog_posts index already exists");
            }
        } catch (Exception e) {
            log.error("Failed to create blog_posts index", e);
        }
    }

    private void createAuditLogsIndex() {
        try {
            String indexName = "audit_logs";

            boolean exists = elasticsearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();

            if (!exists) {
                log.info("Creating audit_logs index...");

                ClassPathResource resource = new ClassPathResource("elasticsearch/audit-logs-config.json");
                try (InputStream inputStream = resource.getInputStream()) {
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
                    log.info("Successfully created audit_logs index");
                }
            } else {
                log.info("audit_logs index already exists");
            }
        } catch (Exception e) {
            log.error("Failed to create audit_logs index", e);
        }
    }
}
