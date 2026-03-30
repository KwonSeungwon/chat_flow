package com.chatflow.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUrl;

    @Bean
    public RestClient elasticsearchRestClient() {
        return RestClient.builder(HttpHost.create(elasticsearchUrl)).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // ES에서 반환하는 다양한 날짜 형식 처리
        SimpleModule dateModule = new SimpleModule();
        dateModule.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String text = p.getText().trim();
                if (text.endsWith("Z")) {
                    text = text.substring(0, text.length() - 1);
                }
                // "2026-03-30" (date only) → midnight
                if (text.length() == 10) {
                    return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
                }
                // "2026-03-30T12:20:59" or "2026-03-30T12:20:59.564"
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        });
        mapper.registerModule(dateModule);

        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(mapper));
        return new ElasticsearchClient(transport);
    }
}
