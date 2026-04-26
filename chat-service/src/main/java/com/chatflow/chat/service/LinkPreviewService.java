package com.chatflow.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * URL 링크 프리뷰(OG 태그) 조회 서비스.
 * SSRF 방어: DNS rebinding 방지를 위해 IP를 한 번만 해석하고 검증된 IP로 직접 요청.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkPreviewService {

    private static final int LINK_PREVIEW_MAX_BYTES = 1_048_576; // 1 MB
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
    private static final String CACHE_PREFIX = "chatflow:link-preview:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final RestClient restClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private record ResolvedUrl(String safeUri, String originalHost) {}

    private ResolvedUrl validateAndResolveUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("URL must not be blank");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http/https schemes are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Missing host in URL");
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                    || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("Access to private/internal addresses is forbidden");
            }
            // DNS rebinding 방지: 해석된 IP 주소로 URI를 직접 구성 -- 재조회 없이 이 IP로 요청
            String ipLiteral = addr instanceof Inet6Address
                    ? "[" + addr.getHostAddress() + "]"
                    : addr.getHostAddress();
            String safeUri = new URI(
                    scheme, null, ipLiteral, uri.getPort(),
                    uri.getRawPath(), uri.getRawQuery(), null
            ).toString();
            return new ResolvedUrl(safeUri, host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Failed to build safe URI: " + e.getMessage());
        }
    }

    public Map<String, String> fetch(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        String cacheKey = CACHE_PREFIX + Math.abs(url.hashCode());
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.debug("Cache parse failed, refetching: {}", e.getMessage());
            }
        }
        try {
            ResolvedUrl resolved = validateAndResolveUrl(url);
            String html = restClient.get()
                    .uri(resolved.safeUri())
                    .header("Host", resolved.originalHost())
                    .header("User-Agent", "Mozilla/5.0 ChatFlow-Bot")
                    .exchange((req, resp) -> {
                        // 응답 크기 제한: Content-Length 헤더 사전 확인
                        long contentLength = resp.getHeaders().getContentLength();
                        if (contentLength > LINK_PREVIEW_MAX_BYTES) {
                            throw new java.io.IOException("Response Content-Length exceeds 1MB limit");
                        }
                        // 스트림에서 최대 1MB+1 바이트만 읽어 초과 여부 확인
                        byte[] buf = resp.getBody().readNBytes(LINK_PREVIEW_MAX_BYTES + 1);
                        if (buf.length > LINK_PREVIEW_MAX_BYTES) {
                            throw new java.io.IOException("Response body exceeds 1MB limit");
                        }
                        return new String(buf, StandardCharsets.UTF_8);
                    });
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
        }
        return result;
    }

    private void extractOg(String html, String property, Map<String, String> result, String key) {
        // property -> content 순서 (일반적)
        var p1 = Pattern.compile(
                "meta[^>]+property=[\"']" + property + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
        var m1 = p1.matcher(html);
        if (m1.find()) { result.put(key, m1.group(1)); return; }
        // content -> property 순서 (일부 사이트)
        var p2 = Pattern.compile(
                "meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + property + "[\"']",
                Pattern.CASE_INSENSITIVE);
        var m2 = p2.matcher(html);
        if (m2.find()) result.put(key, m2.group(1));
    }
}
