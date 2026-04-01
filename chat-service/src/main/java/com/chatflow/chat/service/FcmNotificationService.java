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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

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

    @Value("${firebase.service-account-path:classpath:firebase-service-account.json}")
    private Resource serviceAccountResource;

    private FirebaseMessaging messaging;

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
        if (messaging == null) return;
        try {
            messaging.unsubscribeFromTopicAsync(List.of(token), "room-" + roomId);
            log.debug("FCM unsubscribed token from room-{}", roomId);
        } catch (Exception e) {
            log.warn("FCM unsubscribe failed: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return messaging != null;
    }
}
