package com.gossiplog.chat.service.room;

import com.gossiplog.chat.result.ChatErrorCode;
import com.gossiplog.chat.result.Result;
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
    private static final String KEY_PREFIX = "gossiplog:invite:";
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redisTemplate;

    @Value("${gossiplog.frontend.base-url:https://app.gossiplog.com}")
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

    public Result<String, ChatErrorCode> resolveToken(String token) {
        String roomId = redisTemplate.opsForValue().get(KEY_PREFIX + token);
        if (roomId == null || roomId.isBlank()) {
            return Result.err(ChatErrorCode.GONE, "초대 링크가 만료되었거나 유효하지 않습니다.");
        }
        return Result.ok(roomId);
    }
}
