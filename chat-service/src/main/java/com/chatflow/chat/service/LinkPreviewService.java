package com.chatflow.chat.service;

import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * URL 링크 프리뷰(OG 태그) 조회 서비스.
 * SSRF 방어: 리다이렉트를 자동으로 따라가지 않고 직접 처리하며(최대 3 hop),
 * 매 hop마다 호스트가 해석되는 모든 주소를 검증한다.
 */
@Slf4j
@Service
public class LinkPreviewService {

    private static final int LINK_PREVIEW_MAX_BYTES = 1_048_576; // 1 MB
    private static final int MAX_REDIRECTS = 3;
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Pattern[]> OG_PATTERNS = Map.of(
            "og:title", ogPatterns("og:title"),
            "og:description", ogPatterns("og:description"),
            "og:image", ogPatterns("og:image"));
    private static final String CACHE_PREFIX = "chatflow:link-preview:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(3000))
            .build();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Predicate<InetAddress> blockedPolicy;

    @Autowired
    public LinkPreviewService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, LinkPreviewService::isBlocked);
    }

    /** 테스트용: 주소 차단 정책을 교체할 수 있다. */
    LinkPreviewService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                       Predicate<InetAddress> blockedPolicy) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.blockedPolicy = blockedPolicy;
    }

    private static Pattern[] ogPatterns(String property) {
        return new Pattern[] {
                // property -> content 순서 (일반적)
                Pattern.compile("meta[^>]+property=[\"']" + property + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE),
                // content -> property 순서 (일부 사이트)
                Pattern.compile("meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + property + "[\"']",
                        Pattern.CASE_INSENSITIVE)
        };
    }

    /** 내부/예약 대역 여부. */
    static boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (addr instanceof Inet4Address) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            return b0 == 0                                   // 0.0.0.0/8
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)  // CGNAT 100.64.0.0/10
                    || (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) // benchmarking 198.18.0.0/15
                    || b0 >= 240;                            // reserved / broadcast
        }
        if (addr instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC;                    // ULA fc00::/7
        }
        return false;
    }

    private URI validate(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http/https schemes are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Missing host in URL");
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (blockedPolicy.test(addr)) {
                    throw new IllegalArgumentException("Access to private/internal addresses is forbidden");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host);
        }
        return uri;
    }

    private String fetchHtml(String url) throws IOException, InterruptedException {
        URI current;
        try {
            current = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validate(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMillis(8000))
                    .header("User-Agent", "Mozilla/5.0 ChatFlow-Bot")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = resp.body()) {
                int status = resp.statusCode();
                if (status >= 300 && status < 400) {
                    String location = resp.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("Redirect without Location"));
                    current = current.resolve(location);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Unexpected status " + status);
                }
                long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (contentLength > LINK_PREVIEW_MAX_BYTES) {
                    throw new IOException("Response Content-Length exceeds 1MB limit");
                }
                byte[] buf = body.readNBytes(LINK_PREVIEW_MAX_BYTES + 1);
                if (buf.length > LINK_PREVIEW_MAX_BYTES) {
                    throw new IOException("Response body exceeds 1MB limit");
                }
                return new String(buf, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Too many redirects");
    }

    public Result<Map<String, String>, ChatErrorCode> fetch(String url) {
        if (url == null || url.isBlank()) {
            return Result.err(ChatErrorCode.INVALID_INPUT, "url이 필요합니다.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        String cacheKey = CACHE_PREFIX + (url.length() > 200 ? url.substring(0, 200) : url);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return Result.ok(objectMapper.readValue(cached, new TypeReference<Map<String, String>>() {}));
            } catch (Exception e) {
                log.debug("Cache parse failed, refetching: {}", e.getMessage());
            }
        }
        try {
            String html = fetchHtml(url);
            if (html != null) {
                result.put("url", url);
                extractOg(html, "og:title", result, "title");
                extractOg(html, "og:description", result, "description");
                extractOg(html, "og:image", result, "image");
                if (!result.containsKey("title")) {
                    var m = TITLE_PATTERN.matcher(html);
                    if (m.find()) result.put("title", m.group(1).trim());
                }
            }
            if (!result.isEmpty()) {
                try {
                    redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), CACHE_TTL);
                } catch (Exception e) {
                    log.debug("Cache write failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Link preview fetch failed: {}", e.getMessage());
            // Treat transient fetch failures (network timeout, SSRF reject, malformed
            // response) as Result.ok(empty) — the frontend renders missing preview
            // gracefully and a 500 here would add ops noise without UX value.
            // INTERNAL_ERROR is reserved for genuine internal failures.
        }
        return Result.ok(result);
    }

    private void extractOg(String html, String property, Map<String, String> result, String key) {
        for (Pattern p : OG_PATTERNS.get(property)) {
            var m = p.matcher(html);
            if (m.find()) {
                result.put(key, m.group(1));
                return;
            }
        }
    }
}
