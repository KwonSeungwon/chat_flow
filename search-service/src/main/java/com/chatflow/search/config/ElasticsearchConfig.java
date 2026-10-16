package com.chatflow.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.chatflow.common.config.FlexibleLocalDateTimeDeserializer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Low-level Elasticsearch client wiring. The RestClient is built from the standard
 * {@code spring.elasticsearch.*} properties (comma-separated uris, username/password,
 * connection/socket timeouts, path prefix) plus the trust store configured under
 * {@code spring.elasticsearch.ssl.*} in application-prod.yml.
 */
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.ssl.trust-store:}")
    private String trustStorePath;

    @Value("${spring.elasticsearch.ssl.trust-store-password:}")
    private String trustStorePassword;

    @Bean
    public RestClient elasticsearchRestClient(ElasticsearchProperties properties) {
        return buildRestClient(properties, trustStorePath, trustStorePassword);
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(elasticsearchObjectMapper()));
        return new ElasticsearchClient(transport);
    }

    static RestClient buildRestClient(ElasticsearchProperties properties,
                                      String trustStorePath, String trustStorePassword) {
        HttpHost[] hosts = properties.getUris().stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);

        RestClientBuilder builder = RestClient.builder(hosts);
        if (StringUtils.hasText(properties.getPathPrefix())) {
            builder.setPathPrefix(properties.getPathPrefix());
        }
        builder.setRequestConfigCallback(rc -> rc
                .setConnectTimeout((int) properties.getConnectionTimeout().toMillis())
                .setSocketTimeout((int) properties.getSocketTimeout().toMillis()));

        BasicCredentialsProvider credentials = credentialsOrNull(properties);
        SSLContext sslContext = sslContextOrNull(trustStorePath, trustStorePassword);
        builder.setHttpClientConfigCallback(http -> {
            if (credentials != null) {
                http.setDefaultCredentialsProvider(credentials);
            }
            if (sslContext != null) {
                http.setSSLContext(sslContext);
            }
            return http;
        });
        return builder.build();
    }

    private static BasicCredentialsProvider credentialsOrNull(ElasticsearchProperties properties) {
        if (!StringUtils.hasText(properties.getUsername())) {
            return null;
        }
        BasicCredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword()));
        return provider;
    }

    private static SSLContext sslContextOrNull(String trustStorePath, String trustStorePassword) {
        if (!StringUtils.hasText(trustStorePath)) {
            return null;
        }
        char[] password = StringUtils.hasText(trustStorePassword) ? trustStorePassword.toCharArray() : null;
        try {
            return SSLContexts.custom().loadTrustMaterial(new File(trustStorePath), password).build();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Failed to load Elasticsearch trust store: " + trustStorePath, e);
        }
    }

    /**
     * Mapper for documents exchanged with ES. Timestamps accept everything
     * {@link FlexibleLocalDateTimeDeserializer} does, plus date-only values
     * ("2026-03-30" → midnight) that ES may hold for older documents.
     */
    static ObjectMapper elasticsearchObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        SimpleModule dateModule = new SimpleModule();
        dateModule.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String text = p.getText().trim();
                if (text.length() == 10) {
                    return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
                }
                return super.deserialize(p, ctxt);
            }
        });
        mapper.registerModule(dateModule);
        return mapper;
    }
}
