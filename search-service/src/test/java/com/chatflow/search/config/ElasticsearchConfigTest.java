package com.chatflow.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ElasticsearchConfig — RestClient honours spring.elasticsearch.* settings")
class ElasticsearchConfigTest {

    private HttpServer server;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                // Challenge like ES does with security enabled.
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"security\"");
                exchange.sendResponseHeaders(401, -1);
            } else {
                lastAuthorization.set(auth);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String serverUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("username/password are sent as basic auth")
    void credentialsApplied() throws Exception {
        ElasticsearchProperties props = new ElasticsearchProperties();
        props.setUris(List.of(serverUri()));
        props.setUsername("elastic");
        props.setPassword("test-password");

        try (RestClient client = ElasticsearchConfig.buildRestClient(props, "", "")) {
            Response response = client.performRequest(new Request("GET", "/"));
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        }

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:test-password".getBytes(StandardCharsets.UTF_8));
        assertThat(lastAuthorization.get()).isEqualTo(expected);
    }

    @Test
    @DisplayName("comma-separated uris become separate nodes")
    void commaSeparatedUris_allNodesRegistered() throws Exception {
        ElasticsearchProperties props = new ElasticsearchProperties();
        props.setUris(List.of("http://es-0:9200", " http://es-1:9200 "));

        try (RestClient client = ElasticsearchConfig.buildRestClient(props, "", "")) {
            assertThat(client.getNodes()).extracting(Node::getHost)
                    .containsExactly(HttpHost.create("http://es-0:9200"), HttpHost.create("http://es-1:9200"));
        }
    }

    @Test
    @DisplayName("ES client mapper reads date-only, Z-suffixed and offset timestamps")
    void mapperReadsFlexibleTimestamps() throws Exception {
        ObjectMapper mapper = ElasticsearchConfig.elasticsearchObjectMapper();

        assertThat(mapper.readValue("\"2026-03-30\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 3, 30, 0, 0));
        assertThat(mapper.readValue("\"2026-03-30T12:20:59.564Z\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 3, 30, 12, 20, 59, 564_000_000));
        assertThat(mapper.readValue("\"2026-03-30T12:20:59+09:00\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 3, 30, 12, 20, 59));
    }
}
