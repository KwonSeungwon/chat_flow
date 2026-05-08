package com.chatflow.chat.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Firebase Cloud Messaging — topic-based push notifications.
 * Gracefully disabled when service account is not configured.
 *
 * Topic convention: "room-{chatRoomId}"
 * Devices subscribe/unsubscribe per room; notifications are broadcast to the topic.
 */
@Slf4j
@Service
public class FcmNotificationService {

    private static final String ROOMS_KEY_PREFIX = "chatflow:fcm:rooms:";

    @Value("${firebase.service-account-path:classpath:firebase-service-account.json}")
    private Resource serviceAccountResource;

    private final StringRedisTemplate redisTemplate;
    private FirebaseMessaging messaging;

    public FcmNotificationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void init() {
        if (!serviceAccountResource.exists()) {
            log.warn("Firebase service account not found — FCM push notifications disabled");
            return;
        }
        try {
            GoogleCredentials credentials =
                GoogleCredentials.fromStream(serviceAccountResource.getInputStream());
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(
                    FirebaseOptions.builder().setCredentials(credentials).build())
                : FirebaseApp.getInstance();
            this.messaging = FirebaseMessaging.getInstance(app);
            log.info("Firebase Admin SDK initialized — FCM enabled");
        } catch (IOException e) {
            log.error("Firebase Admin SDK initialization failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Notification sending
    // -------------------------------------------------------------------------

    /** Sends a chat message notification to the room topic (async, non-blocking). */
    @Async("persistenceExecutor")
    public void sendMessageNotification(String roomId, String sender, String content) {
        if (messaging == null) return;
        String preview = content.length() > 60 ? content.substring(0, 57) + "..." : content;
        Message msg = Message.builder()
            .setTopic("room-" + roomId)
            .setNotification(Notification.builder()
                .setTitle(sender)
                .setBody(preview)
                .build())
            .putData("roomId", roomId)
            .putData("sender", sender)
            .build();
        try {
            messaging.sendAsync(msg);
        } catch (Exception e) {
            log.warn("FCM send failed for room {}: {}", roomId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Topic subscription management
    // -------------------------------------------------------------------------

    @Async("persistenceExecutor")
    public void subscribeToRoom(String token, String roomId) {
        redisTemplate.opsForSet().add(ROOMS_KEY_PREFIX + token, roomId);
        if (messaging == null) return;
        try {
            messaging.subscribeToTopicAsync(List.of(token), "room-" + roomId);
            log.debug("FCM subscribed token to room-{}", roomId);
        } catch (Exception e) {
            log.warn("FCM subscribe failed: {}", e.getMessage());
        }
    }

    @Async("persistenceExecutor")
    public void unsubscribeFromRoom(String token, String roomId) {
        redisTemplate.opsForSet().remove(ROOMS_KEY_PREFIX + token, roomId);
        if (messaging == null) return;
        try {
            messaging.unsubscribeFromTopicAsync(List.of(token), "room-" + roomId);
            log.debug("FCM unsubscribed token from room-{}", roomId);
        } catch (Exception e) {
            log.warn("FCM unsubscribe failed: {}", e.getMessage());
        }
    }

    /**
     * Removes the token from every room topic it was subscribed to.
     * Used on tab-close so push notifications stop arriving while the user is away.
     * Intentionally synchronous — the tab-close caller (fetch keepalive) needs the
     * call to complete fully before the request thread returns.
     */
    public void unsubscribeAll(String token) {
        String key = ROOMS_KEY_PREFIX + token;
        Set<String> rooms = redisTemplate.opsForSet().members(key);
        if (rooms == null || rooms.isEmpty()) return;
        if (messaging != null) {
            for (String roomId : rooms) {
                try {
                    messaging.unsubscribeFromTopicAsync(List.of(token), "room-" + roomId);
                } catch (Exception e) {
                    log.warn("FCM unsubscribe-all (room {}) failed: {}", roomId, e.getMessage());
                }
            }
        }
        redisTemplate.delete(key);
        log.debug("FCM unsubscribed token from {} rooms", rooms.size());
    }

    public boolean isEnabled() {
        return messaging != null;
    }
}
