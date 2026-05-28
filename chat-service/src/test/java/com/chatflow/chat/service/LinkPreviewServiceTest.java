package com.chatflow.chat.service;

import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersUriSpec;
import org.springframework.web.client.RestClient.RequestHeadersSpec;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ExchangeFunction;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LinkPreviewService covering Redis cache hits/misses,
 * HTTP fetch + OG-tag parsing, error handling, and max content-size guard.
 */
@ExtendWith(MockitoExtension.class)
class LinkPreviewServiceTest {

    @Mock private RestClient restClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LinkPreviewService linkPreviewService;

    private static final String TEST_URL = "https://example.com/page";
    private static final String CACHE_KEY = "chatflow:link-preview:" + TEST_URL;

    @BeforeEach
    void setUp() {
        linkPreviewService = new LinkPreviewService(restClient, redisTemplate, objectMapper);
    }

    // ── Cache hit ────────────────────────────────────────────────

    @Test
    void returns_cached_preview_when_redis_hit() throws Exception {
        Map<String, String> cached = Map.of(
                "url", TEST_URL,
                "title", "Cached Title",
                "description", "Cached Desc",
                "image", "https://example.com/img.png");
        String json = objectMapper.writeValueAsString(cached);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(json);

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(TEST_URL);

        assertTrue(result.isSuccess());
        assertEquals("Cached Title", result.value().get("title"));
        assertEquals("Cached Desc", result.value().get("description"));
        assertEquals("https://example.com/img.png", result.value().get("image"));

        // RestClient must never be called when cache hits
        verifyNoInteractions(restClient);
    }

    // ── Cache miss → HTTP fetch + cache write ────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void fetches_and_caches_when_redis_miss() throws Exception {
        // Cache miss
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        // Build canned HTML with OG tags
        String html = """
                <html><head>
                <title>Fallback Title</title>
                <meta property="og:title" content="OG Title" />
                <meta property="og:description" content="OG Description" />
                <meta property="og:image" content="https://example.com/og.png" />
                </head><body></body></html>
                """;

        // Stub the RestClient chain: get() -> uri() -> header() -> exchange()
        RequestHeadersUriSpec uriSpec = mock(RequestHeadersUriSpec.class);
        RequestHeadersSpec headersSpec = mock(RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);

        // Capture the ExchangeFunction and invoke it with a mock response
        when(headersSpec.exchange(any(ExchangeFunction.class))).thenAnswer(invocation -> {
            ExchangeFunction<String> fn = invocation.getArgument(0);
            HttpRequest mockReq = mock(HttpRequest.class);
            ConvertibleClientHttpResponse mockResp = mock(ConvertibleClientHttpResponse.class);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentLength(html.length());
            when(mockResp.getHeaders()).thenReturn(headers);
            when(mockResp.getBody()).thenReturn(
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            return fn.exchange(mockReq, mockResp);
        });

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(TEST_URL);

        assertTrue(result.isSuccess());
        // OG tags take precedence over <title>
        assertEquals("OG Title", result.value().get("title"));
        assertEquals("OG Description", result.value().get("description"));
        assertEquals("https://example.com/og.png", result.value().get("image"));
        assertEquals(TEST_URL, result.value().get("url"));

        // Verify cache write with correct key, JSON content, and 1-hour TTL
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(keyCaptor.capture(), jsonCaptor.capture(), ttlCaptor.capture());

        assertEquals(CACHE_KEY, keyCaptor.getValue());
        assertEquals(Duration.ofHours(1), ttlCaptor.getValue());

        Map<String, String> written = objectMapper.readValue(
                jsonCaptor.getValue(), new TypeReference<>() {});
        assertEquals("OG Title", written.get("title"));
    }

    // ── HTTP failure → empty map, no cache write ─────────────────

    @SuppressWarnings("unchecked")
    @Test
    void returns_empty_map_when_http_fails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        // Stub RestClient chain to throw on exchange()
        RequestHeadersUriSpec uriSpec = mock(RequestHeadersUriSpec.class);
        RequestHeadersSpec headersSpec = mock(RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.exchange(any(ExchangeFunction.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(TEST_URL);

        // Network failure → ok(empty), not INTERNAL_ERROR (ops noise)
        assertTrue(result.isSuccess());
        assertTrue(result.value().isEmpty());

        // No cache write should occur
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ── Max content size: Content-Length exceeds 1 MB ─────────────

    @SuppressWarnings("unchecked")
    @Test
    void respects_max_content_size_via_content_length_header() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        RequestHeadersUriSpec uriSpec = mock(RequestHeadersUriSpec.class);
        RequestHeadersSpec headersSpec = mock(RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);

        // Exchange invokes the lambda which checks Content-Length > 1MB
        when(headersSpec.exchange(any(ExchangeFunction.class))).thenAnswer(invocation -> {
            ExchangeFunction<String> fn = invocation.getArgument(0);
            HttpRequest mockReq = mock(HttpRequest.class);
            ConvertibleClientHttpResponse mockResp = mock(ConvertibleClientHttpResponse.class);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentLength(2_000_000L); // 2 MB — exceeds 1 MB limit
            when(mockResp.getHeaders()).thenReturn(headers);
            return fn.exchange(mockReq, mockResp);
        });

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(TEST_URL);

        // IOException inside exchange → caught by outer catch → ok(empty)
        assertTrue(result.isSuccess());
        assertTrue(result.value().isEmpty());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ── Max content size: body stream exceeds 1 MB ───────────────

    @SuppressWarnings("unchecked")
    @Test
    void respects_max_content_size_via_body_stream() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        RequestHeadersUriSpec uriSpec = mock(RequestHeadersUriSpec.class);
        RequestHeadersSpec headersSpec = mock(RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);

        // Content-Length header not set (or lies), but body stream is > 1MB
        when(headersSpec.exchange(any(ExchangeFunction.class))).thenAnswer(invocation -> {
            ExchangeFunction<String> fn = invocation.getArgument(0);
            HttpRequest mockReq = mock(HttpRequest.class);
            ConvertibleClientHttpResponse mockResp = mock(ConvertibleClientHttpResponse.class);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentLength(-1); // unknown length
            when(mockResp.getHeaders()).thenReturn(headers);
            // Body larger than 1 MB + 1
            byte[] oversized = new byte[1_048_576 + 100];
            when(mockResp.getBody()).thenReturn(new ByteArrayInputStream(oversized));
            return fn.exchange(mockReq, mockResp);
        });

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(TEST_URL);

        // Body stream exceeds 1MB → caught by outer catch → ok(empty)
        assertTrue(result.isSuccess());
        assertTrue(result.value().isEmpty());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ── Null/blank URL → INVALID_INPUT ──────────────────────────

    @Test
    void returns_INVALID_INPUT_for_null_url() {
        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(null);

        assertTrue(result.isFailure());
        assertEquals(ChatErrorCode.INVALID_INPUT, result.error());
        verifyNoInteractions(restClient);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void returns_INVALID_INPUT_for_blank_url() {
        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch("  ");

        assertTrue(result.isFailure());
        assertEquals(ChatErrorCode.INVALID_INPUT, result.error());
        verifyNoInteractions(restClient);
        verifyNoInteractions(redisTemplate);
    }
}
