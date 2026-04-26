package com.chatflow.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InviteLinkService {
    private static final String KEY_PREFIX = "chatflow:invite:";
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redisTemplate;

    @Value("${chatflow.frontend.base-url:https://app.chatflow.ai.kr}")
    private String frontendBaseUrl;

    public String getInviteUrl(String token) {
        return frontendBaseUrl + "/invite/" + token;
    }

    public String createInviteToken(String roomId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + token, roomId, TTL);
        log.info("Invite link created: token={} roomId={}", token, roomId);
        return token;
    }

    public String resolveToken(String token) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + token);
    }
}
