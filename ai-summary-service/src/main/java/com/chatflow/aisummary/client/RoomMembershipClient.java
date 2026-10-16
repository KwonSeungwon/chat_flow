package com.chatflow.aisummary.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Room-membership lookups against chat-service's {@code /internal/rooms/**}
 * endpoints, authenticated with the shared internal secret.
 *
 * <p>Any failure surfaces as {@link MembershipCheckUnavailableException}
 * (→ 503); callers must never treat a failure as "member".
 */
@Slf4j
@Component
public class RoomMembershipClient {

    static final String SECRET_HEADER = "X-Gateway-Secret";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public RoomMembershipClient(
            RestClient.Builder builder,
            @Value("${chatflow.chat-service.url:http://chat-service:8080}") String baseUrl,
            @Value("${GATEWAY_INTERNAL_SECRET:}") String internalSecret) {
        this(builder.baseUrl(baseUrl).requestFactory(timeoutRequestFactory()).build(), internalSecret);
    }

    RoomMembershipClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret == null ? "" : internalSecret;
    }

    /** {@code true} on 204, {@code false} on 404; anything else throws. */
    public boolean isMember(String roomId, String userId) {
        try {
            return restClient.get()
                    .uri("/internal/rooms/{roomId}/members/{userId}", roomId, userId)
                    .header(SECRET_HEADER, internalSecret)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) return true;
                        if (status == 404) return false;
                        throw new MembershipCheckUnavailableException(
                                "chat-service membership check returned " + status, null);
                    });
        } catch (RestClientException e) {
            log.error("chat-service membership check failed: roomId={}, error={}", roomId, e.getMessage());
            throw new MembershipCheckUnavailableException("chat-service membership check failed", e);
        }
    }

    /** Room ids the user is a member of; throws on any failure. */
    public List<String> memberRoomIds(String userId) {
        try {
            List<String> ids = restClient.get()
                    .uri("/internal/rooms/members/{userId}/room-ids", userId)
                    .header(SECRET_HEADER, internalSecret)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() { });
            return ids == null ? List.of() : ids;
        } catch (RestClientException e) {
            log.error("chat-service member room-ids lookup failed: error={}", e.getMessage());
            throw new MembershipCheckUnavailableException("chat-service member room-ids lookup failed", e);
        }
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }
}
