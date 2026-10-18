package com.gossiplog.chat.service;

import com.gossiplog.chat.result.ChatErrorCode;
import com.gossiplog.chat.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * URL 링크 프리뷰(OG 태그) 조회 서비스.
 * SSRF 방어: 리다이렉트를 자동으로 따라가지 않고 직접 처리하며(최대 3 hop),
 * 매 hop마다 호스트가 해석되는 모든 주소를 검증하고 검증한 주소로만 연결한다.
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
    private static final String CACHE_PREFIX = "gossiplog:link-preview:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final long READ_TIMEOUT_MS = 8000;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "link-preview-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Predicate<InetAddress> blockedPolicy;
    private final HostResolver resolver;

    /** 호스트 이름 해석기. 테스트에서 리바인딩 DNS를 흉내 내려고 교체한다. */
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    @Autowired
    public LinkPreviewService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, LinkPreviewService::isBlocked);
    }

    /** 테스트용: 주소 차단 정책을 교체할 수 있다. */
    LinkPreviewService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                       Predicate<InetAddress> blockedPolicy) {
        this(redisTemplate, objectMapper, blockedPolicy, InetAddress::getAllByName);
    }

    /** 테스트용: 차단 정책과 호스트 해석기를 교체할 수 있다. */
    LinkPreviewService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                       Predicate<InetAddress> blockedPolicy, HostResolver resolver) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.blockedPolicy = blockedPolicy;
        this.resolver = resolver;
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
            if ((b[0] & 0xFE) == 0xFC) return true;          // ULA fc00::/7
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) return true; // Teredo 2001:0000::/32
            // IPv4 embedded in IPv6: check the embedded address against the IPv4 rules
            Inet4Address embedded = embeddedIpv4(b);
            return embedded != null && isBlocked(embedded);
        }
        return false;
    }

    /** IPv4-compatible ::a.b.c.d, NAT64 64:ff9b::/96, 6to4 2002::/16 → embedded IPv4, else null. */
    private static Inet4Address embeddedIpv4(byte[] b) {
        int from;
        if (allZero(b, 0, 12)) {
            from = 12;                                       // ::a.b.c.d
        } else if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && allZero(b, 4, 12)) {
            from = 12;                                       // 64:ff9b::a.b.c.d
        } else if (b[0] == 0x20 && b[1] == 0x02) {
            from = 2;                                        // 2002:aabb:ccdd::/48
        } else {
            return null;
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, from, from + 4));
        } catch (UnknownHostException e) {
            return null;                                     // unreachable: 4-byte array
        }
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    /** URI.getHost() keeps the brackets of an IPv6 literal; the resolver may get either form. */
    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /** Whole-URL digest: bounded key length without collapsing URLs that share a prefix. */
    private static String sha256Hex(String url) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 스킴·호스트를 검사하고 호스트를 한 번 해석해 모든 주소를 검증한다. 연결은 이 주소로만 한다. */
    private InetAddress[] validate(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http/https schemes are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Missing host in URL");
        try {
            InetAddress[] addresses = resolver.resolve(host);
            for (InetAddress addr : addresses) {
                if (blockedPolicy.test(addr)) {
                    throw new IllegalArgumentException("Access to private/internal addresses is forbidden");
                }
            }
            return addresses;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host);
        }
    }

    /**
     * 검증한 주소만 돌려주는 DnsResolver. HTTP 클라이언트가 DNS를 다시 조회하지 않으므로
     * 검증과 연결 사이에 DNS 응답이 바뀌어도(리바인딩) 사설 주소로 연결되지 않는다.
     * Host 헤더·SNI·인증서 검증은 원래 호스트 이름을 그대로 쓴다.
     */
    static DnsResolver pinnedResolver(String pinnedHost, InetAddress[] pinned) {
        String key = stripBrackets(pinnedHost);
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (key.equalsIgnoreCase(stripBrackets(host))) return pinned.clone();
                throw new UnknownHostException("Unvalidated host: " + host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private String fetchHtml(String url) throws IOException {
        URI current;
        try {
            current = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            InetAddress[] validated = validate(current);
            HttpGet request = new HttpGet(current);
            request.setHeader("User-Agent", "Mozilla/5.0 Gossiplog-Bot");
            CloseableHttpClient client = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setDnsResolver(pinnedResolver(current.getHost(), validated))
                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                    .setConnectTimeout(Timeout.ofMilliseconds(3000))
                                    .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                                    .build())
                            .build())
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                            .build())
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .build();
            // 응답 수신과 본문 읽기 전체에 데드라인을 걸고 만료 시 요청을 중단(연결 폐기)한다
            ScheduledFuture<?> watchdog = WATCHDOG.schedule(request::cancel, READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                ClassicHttpResponse resp = client.executeOpen(null, request, null);
                int status = resp.getCode();
                if (status >= 300 && status < 400) {
                    Header location = resp.getFirstHeader("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    current = current.resolve(location.getValue());
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Unexpected status " + status);
                }
                HttpEntity entity = resp.getEntity();
                if (entity == null) throw new IOException("Empty response");
                if (entity.getContentLength() > LINK_PREVIEW_MAX_BYTES) {
                    throw new IOException("Response Content-Length exceeds 1MB limit");
                }
                byte[] buf = entity.getContent().readNBytes(LINK_PREVIEW_MAX_BYTES + 1);
                if (buf.length > LINK_PREVIEW_MAX_BYTES) {
                    throw new IOException("Response body exceeds 1MB limit");
                }
                return new String(buf, StandardCharsets.UTF_8);
            } finally {
                watchdog.cancel(false);
                // 남은 본문을 끝까지 읽지(drain) 않고 연결을 버린다
                request.cancel();
                client.close(CloseMode.IMMEDIATE);
            }
        }
        throw new IOException("Too many redirects");
    }

    public Result<Map<String, String>, ChatErrorCode> fetch(String url) {
        if (url == null || url.isBlank()) {
            return Result.err(ChatErrorCode.INVALID_INPUT, "url이 필요합니다.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        String cacheKey = CACHE_PREFIX + sha256Hex(url);
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
