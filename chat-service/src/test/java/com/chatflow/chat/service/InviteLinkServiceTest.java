package com.chatflow.chat.service;

import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InviteLinkService covering token creation with TTL,
 * token resolution, and invite URL construction.
 */
@ExtendWith(MockitoExtension.class)
class InviteLinkServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private InviteLinkService inviteLinkService;

    private static final String ROOM_ID = "room-42";
    private static final String BASE_URL = "https://app.chatflow.ai.kr";

    @BeforeEach
    void setUp() throws Exception {
        inviteLinkService = new InviteLinkService(redisTemplate);

        // Set @Value field via reflection (no Spring context in unit tests)
        Field baseUrlField = InviteLinkService.class.getDeclaredField("frontendBaseUrl");
        baseUrlField.setAccessible(true);
        baseUrlField.set(inviteLinkService, BASE_URL);
    }

    // ── CreateInviteToken ───────────────────────────────────────────

    @Nested
    class CreateInviteToken {

        @Test
        void createInviteToken_writes_roomId_under_token_key_with_24h_ttl() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            String token = inviteLinkService.createInviteToken(ROOM_ID);

            assertNotNull(token);
            assertFalse(token.isEmpty());

            // Verify Redis set was called with the correct key prefix, value, and TTL
            verify(valueOperations).set(
                    eq("chatflow:invite:" + token),
                    eq(ROOM_ID),
                    eq(Duration.ofHours(24)));
        }
    }

    // ── ResolveToken ────────────────────────────────────────────────

    @Nested
    class ResolveToken {

        @Test
        void resolveToken_returns_roomId_when_key_present() {
            String token = "some-valid-token";
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("chatflow:invite:" + token)).thenReturn(ROOM_ID);

            Result<String, ChatErrorCode> result = inviteLinkService.resolveToken(token);

            assertTrue(result.isSuccess());
            assertEquals(ROOM_ID, result.value());
        }

        @Test
        void resolveToken_returns_GONE_when_key_expired_or_missing() {
            String token = "expired-or-missing-token";
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("chatflow:invite:" + token)).thenReturn(null);

            Result<String, ChatErrorCode> result = inviteLinkService.resolveToken(token);

            assertTrue(result.isFailure());
            assertEquals(ChatErrorCode.GONE, result.error());
        }

        @Test
        void resolveToken_returns_GONE_when_key_blank() {
            String token = "blank-value-token";
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("chatflow:invite:" + token)).thenReturn("  ");

            Result<String, ChatErrorCode> result = inviteLinkService.resolveToken(token);

            assertTrue(result.isFailure());
            assertEquals(ChatErrorCode.GONE, result.error());
        }
    }

    // ── GetInviteUrl ────────────────────────────────────────────────

    @Nested
    class GetInviteUrl {

        @Test
        void getInviteUrl_concatenates_baseUrl_and_token() {
            String token = "abc-123-def";

            String url = inviteLinkService.getInviteUrl(token);

            assertTrue(url.startsWith(BASE_URL));
            assertEquals(BASE_URL + "/invite/" + token, url);
        }

        @Test
        void getInviteUrl_uses_custom_baseUrl() throws Exception {
            String customBase = "https://custom.example.com";
            Field baseUrlField = InviteLinkService.class.getDeclaredField("frontendBaseUrl");
            baseUrlField.setAccessible(true);
            baseUrlField.set(inviteLinkService, customBase);

            String token = "xyz-456";

            String url = inviteLinkService.getInviteUrl(token);

            assertTrue(url.startsWith(customBase));
            assertEquals(customBase + "/invite/" + token, url);
        }
    }
}
