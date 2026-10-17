package com.chatflow.chat.service;

import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LinkPreviewService: Redis 캐시, OG 파싱, 크기 제한, SSRF(리다이렉트 포함) 방어.
 * HTTP 경로는 JDK HttpServer(loopback)로 검증하며, 테스트에서는 loopback만 허용하고
 * 나머지 내부 대역은 실제 isBlocked 정책을 그대로 적용한다.
 */
@ExtendWith(MockitoExtension.class)
class LinkPreviewServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LinkPreviewService linkPreviewService;
    private HttpServer server;
    private String baseUrl;

    private static final String TEST_URL = "https://example.com/page";
    private static final String CACHE_KEY = cacheKey(TEST_URL);

    /** Cache key = prefix + SHA-256(hex) of the whole URL. */
    private static String cacheKey(String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return "chatflow:link-preview:" + java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        linkPreviewService = new LinkPreviewService(redisTemplate, objectMapper,
                a -> !a.isLoopbackAddress() && LinkPreviewService.isBlocked(a));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    private void redirect(String path, String location, AtomicInteger hits) {
        server.createContext(path, ex -> {
            if (hits != null) hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
    }

    private void cacheMiss(String url) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(cacheKey(url))).thenReturn(null);
    }

    // ── Cache hit ────────────────────────────────────────────────

    @Test
    void returns_cached_preview_when_redis_hit() throws Exception {
        Map<String, String> cached = Map.of(
                "url", TEST_URL,
                "title", "Cached Title",
                "description", "Cached Desc",
                "image", "https://example.com/img.png");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(objectMapper.writeValueAsString(cached));

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(TEST_URL);

        assertTrue(result.isSuccess());
        assertEquals("Cached Title", result.value().get("title"));
        assertEquals("Cached Desc", result.value().get("description"));
        assertEquals("https://example.com/img.png", result.value().get("image"));
    }

    @Test
    void urls_sharing_a_long_prefix_do_not_share_a_cache_entry() throws Exception {
        String prefix = "https://example.com/" + "a".repeat(250);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(cacheKey(prefix + "?x=1")))
                .thenReturn(objectMapper.writeValueAsString(Map.of("title", "First")));

        assertEquals("First", linkPreviewService.fetch(prefix + "?x=1").value().get("title"));
        verify(valueOps).get(cacheKey(prefix + "?x=1"));
        assertNotEquals(cacheKey(prefix + "?x=1"), cacheKey(prefix + "?x=2"));
    }

    // ── Cache miss → HTTP fetch + cache write ────────────────────

    @Test
    void fetches_and_caches_when_redis_miss() throws Exception {
        String html = """
                <html><head>
                <title>Fallback Title</title>
                <meta property="og:title" content="OG Title" />
                <meta property="og:description" content="OG Description" />
                <meta property="og:image" content="https://example.com/og.png" />
                </head><body></body></html>
                """;
        respond("/page", 200, html);
        String url = baseUrl + "/page";
        cacheMiss(url);

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(url);

        assertTrue(result.isSuccess());
        assertEquals("OG Title", result.value().get("title"));
        assertEquals("OG Description", result.value().get("description"));
        assertEquals("https://example.com/og.png", result.value().get("image"));
        assertEquals(url, result.value().get("url"));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(cacheKey(url)), jsonCaptor.capture(), ttlCaptor.capture());
        assertEquals(Duration.ofHours(1), ttlCaptor.getValue());
        Map<String, String> written = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        assertEquals("OG Title", written.get("title"));
    }

    @Test
    void falls_back_to_title_tag_and_content_before_property_order() {
        respond("/page", 200, "<title>Plain</title><meta content=\"Desc\" property=\"og:description\">");
        String url = baseUrl + "/page";
        cacheMiss(url);

        Map<String, String> value = linkPreviewService.fetch(url).value();

        assertEquals("Plain", value.get("title"));
        assertEquals("Desc", value.get("description"));
    }

    // ── Failure modes → empty, no cache write ────────────────────

    @Test
    void returns_empty_map_when_http_fails() {
        respond("/page", 500, "boom");
        String url = baseUrl + "/page";
        cacheMiss(url);

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(url);

        assertTrue(result.isSuccess());
        assertTrue(result.value().isEmpty());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void respects_max_content_size() {
        respond("/big", 200, "x".repeat(1_048_576 + 100));
        String url = baseUrl + "/big";
        cacheMiss(url);

        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(url);

        assertTrue(result.isSuccess());
        assertTrue(result.value().isEmpty());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ── SSRF ─────────────────────────────────────────────────────

    @Test
    void preview_redirectToPrivateIp_blocked() throws Exception {
        // 내부 대상 서버: 요청이 오면 hit 기록 + 실제 OG HTML 반환 (가드가 없으면 프리뷰가 채워진다)
        AtomicInteger internalHits = new AtomicInteger();
        HttpServer internal = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        internal.createContext("/secret", ex -> {
            internalHits.incrementAndGet();
            byte[] b = "<title>SECRET</title>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        internal.start();
        try {
            AtomicInteger validations = new AtomicInteger();
            // 첫 hop(공개 호스트 역할)만 허용하고 이후 hop의 주소는 내부 대역으로 간주해 차단
            LinkPreviewService svc = new LinkPreviewService(redisTemplate, objectMapper,
                    a -> validations.incrementAndGet() > 1);
            redirect("/r", "http://localhost:" + internal.getAddress().getPort() + "/secret", null);
            String url = baseUrl + "/r";
            cacheMiss(url);

            Result<Map<String, String>, ChatErrorCode> result = svc.fetch(url);

            assertTrue(result.value().isEmpty());
            assertEquals(0, internalHits.get(), "blocked redirect target must never be requested");
            assertTrue(validations.get() >= 2, "redirect hop must be re-validated");
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        } finally {
            internal.stop(0);
        }
    }

    @Test
    void preview_dnsRebinding_connectsOnlyToValidatedAddress() throws Exception {
        // 첫 해석은 공인(테스트에서는 loopback 서버), 그 뒤 해석은 사설 주소를 돌려주는 리바인딩 리졸버.
        // 검증한 주소로만 연결해야 하므로 해석은 hop당 한 번이고 응답은 공인 주소의 서버에서 와야 한다.
        respond("/page", 200, "<title>Public</title>");
        InetAddress publicAddr = InetAddress.getByAddress("rebind.test", new byte[] {127, 0, 0, 1});
        InetAddress privateAddr = InetAddress.getByAddress("rebind.test", new byte[] {10, 0, 0, 1});
        AtomicInteger resolutions = new AtomicInteger();
        LinkPreviewService svc = new LinkPreviewService(redisTemplate, objectMapper,
                a -> !a.isLoopbackAddress() && LinkPreviewService.isBlocked(a),
                host -> new InetAddress[] {resolutions.incrementAndGet() == 1 ? publicAddr : privateAddr});
        String url = "http://rebind.test:" + server.getAddress().getPort() + "/page";
        cacheMiss(url);

        Map<String, String> value = svc.fetch(url).value();

        assertEquals("Public", value.get("title"));
        assertEquals(1, resolutions.get(), "connection must reuse the validated resolution");
    }

    @Test
    void preview_followsSafeRedirect() {
        redirect("/r", "/final", null);
        respond("/final", 200, "<title>Landed</title>");
        String url = baseUrl + "/r";
        cacheMiss(url);

        assertEquals("Landed", linkPreviewService.fetch(url).value().get("title"));
    }

    @Test
    void preview_tooManyRedirects_givesUp() {
        redirect("/a", "/b", null);
        redirect("/b", "/c", null);
        redirect("/c", "/d", null);
        redirect("/d", "/e", null);
        respond("/e", 200, "<title>Too far</title>");
        String url = baseUrl + "/a";
        cacheMiss(url);

        assertTrue(linkPreviewService.fetch(url).value().isEmpty());
    }

    @Test
    void preview_privateLiteralUrl_blockedWithDefaultPolicy() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/x", ex -> {
            hits.incrementAndGet();
            byte[] b = "<title>REACHED</title>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        LinkPreviewService strict = new LinkPreviewService(redisTemplate, objectMapper);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        assertTrue(strict.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/x").value().isEmpty());
        assertEquals(0, hits.get());
    }

    @Test
    void preview_slowTrickleBody_isCutOffByReadDeadline() {
        server.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 0);
            try {
                var out = ex.getResponseBody();
                for (int i = 0; i < 40; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {
                // client closed the connection
            } finally {
                ex.close();
            }
        });
        String url = baseUrl + "/slow";
        cacheMiss(url);

        long start = System.nanoTime();
        assertTrue(linkPreviewService.fetch(url).value().isEmpty());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 12_000, "read must be bounded, took " + elapsedMs + "ms");
    }

    @Test
    void isBlocked_cgnat_100_64() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("100.64.0.1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("100.127.255.254")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("100.128.0.1")));
    }

    @Test
    void isBlocked_teredoPrefix_2001_0000() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("2001:0:4136:e378:8000:63bf:3fff:fdd2")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("2001:0:ffff::1")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void isBlocked_ipv6Ula_fc00() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("fc00::1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("fd12:3456::1")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("2606:4700::1111")));
    }

    @Test
    void isBlocked_commonInternalAndPublic() throws Exception {
        for (String ip : new String[] {"10.0.0.1", "172.16.0.1", "192.168.1.1", "127.0.0.1",
                "169.254.169.254", "0.0.0.0", "::1", "::ffff:10.0.0.1"}) {
            assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName(ip)), ip);
        }
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("93.184.216.34")));
    }

    @Test
    void isBlocked_ipv4CompatibleIpv6_embeddedPrivate() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("::10.0.0.1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("::127.0.0.1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("::169.254.169.254")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("::8.8.8.8")));
    }

    @Test
    void isBlocked_nat64_64ff9b_embeddedPrivate() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("64:ff9b::10.0.0.1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("64:ff9b::7f00:1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("64:ff9b::a9fe:a9fe")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("64:ff9b::808:808")));
    }

    @Test
    void isBlocked_6to4_2002_embeddedPrivate() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("2002:0a00:0001::1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("2002:7f00:0001::")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("2002:c0a8:0101::1")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("2002:0808:0808::1")));
    }

    @Test
    void isBlocked_ipv6UniqueLocal_fc00_7() throws Exception {
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("fc12::1")));
        assertTrue(LinkPreviewService.isBlocked(InetAddress.getByName("fdff:ffff::1")));
        assertFalse(LinkPreviewService.isBlocked(InetAddress.getByName("fe00::1")));
    }

    // ── IPv6 literal hosts ──────────────────────────────────────

    @Test
    void pinnedResolver_ipv6LiteralHost_matchesWithOrWithoutBrackets() throws Exception {
        InetAddress google = InetAddress.getByName("2001:4860:4860::8888");
        InetAddress[] pinned = {google};
        org.apache.hc.client5.http.DnsResolver r =
                LinkPreviewService.pinnedResolver("[2001:4860:4860::8888]", pinned);

        assertArrayEquals(pinned, r.resolve("2001:4860:4860::8888"));
        assertArrayEquals(pinned, r.resolve("[2001:4860:4860::8888]"));
        assertThrows(java.net.UnknownHostException.class, () -> r.resolve("example.com"));
    }

    @Test
    void preview_ipv6LiteralUrl_isFetchedThroughThePinnedResolver() throws Exception {
        HttpServer v6;
        try {
            v6 = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("IPv6 loopback unavailable: " + e.getMessage());
            return;
        }
        v6.createContext("/page", ex -> {
            byte[] b = "<title>V6</title>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        v6.start();
        try {
            String url = "http://[::1]:" + v6.getAddress().getPort() + "/page";
            cacheMiss(url);

            assertEquals("V6", linkPreviewService.fetch(url).value().get("title"));
        } finally {
            v6.stop(0);
        }
    }

    // ── Null/blank URL → INVALID_INPUT ──────────────────────────

    @Test
    void returns_INVALID_INPUT_for_null_url() {
        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(null);

        assertTrue(result.isFailure());
        assertEquals(ChatErrorCode.INVALID_INPUT, result.error());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void returns_INVALID_INPUT_for_blank_url() {
        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch("  ");

        assertTrue(result.isFailure());
        assertEquals(ChatErrorCode.INVALID_INPUT, result.error());
        verifyNoInteractions(redisTemplate);
    }
}
