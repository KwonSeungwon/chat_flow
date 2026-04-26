import 'dart:convert';
import 'dart:io';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

// Must be a top-level function — runs in a separate isolate when app is killed.
@pragma('vm:entry-point')
Future<void> _onBackgroundMessage(RemoteMessage message) async {
  // Firebase is already initialized by the system when this runs.
  // System displays the notification automatically; nothing extra needed.
  debugPrint('[FCM] Background message: ${message.messageId}');
}

class FcmService {
  FcmService._();

  static const _vapidKey =
      'BILKh58G8pGKBiKJR4gG17LhnkSkxACyzcNV4PqvC61SIhiu5mLeRkZa5AgqKFbP2BQAx0x7PpmrwqDGKZe3qyA';

  static const _channelId = 'chatflow_messages';
  static const _channelName = 'ChatFlow Messages';

  static const _keywordChannelId = 'chatflow_keywords';
  static const _keywordChannelName = 'ChatFlow 키워드 알림';

  static final _messaging = FirebaseMessaging.instance;
  static final _localNotifs = FlutterLocalNotificationsPlugin();

  static Future<void> initialize() async {
    // Request OS-level permission (Android 13+ / iOS)
    await _messaging.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );

    // Android: create high-importance notification channel
    if (!kIsWeb && Platform.isAndroid) {
      await _localNotifs.initialize(
        const InitializationSettings(
          android: AndroidInitializationSettings('@mipmap/ic_launcher'),
        ),
      );
      final androidPlugin = _localNotifs
          .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>();
      await androidPlugin?.createNotificationChannel(
        const AndroidNotificationChannel(
          _channelId,
          _channelName,
          description: 'ChatFlow 채팅 메시지 알림',
          importance: Importance.high,
        ),
      );
      await androidPlugin?.createNotificationChannel(
        const AndroidNotificationChannel(
          _keywordChannelId,
          _keywordChannelName,
          description: 'ChatFlow 키워드 매칭 알림',
          importance: Importance.max,
        ),
      );
    }

    // Register background handler before any other Firebase call
    FirebaseMessaging.onBackgroundMessage(_onBackgroundMessage);

    // Show notification while app is in foreground
    FirebaseMessaging.onMessage.listen(_onForegroundMessage);

    // App opened via notification tap (from background state)
    FirebaseMessaging.onMessageOpenedApp.listen((msg) {
      debugPrint('[FCM] Opened from notification: ${msg.data}');
    });
  }

  /// Returns the FCM registration token for this device/browser.
  static Future<String?> getToken() async {
    if (kIsWeb) {
      return _messaging.getToken(vapidKey: _vapidKey);
    }
    return _messaging.getToken();
  }

  /// Listen for token refreshes (device token can change).
  static Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  // ---------------------------------------------------------------------------

  static Future<void> _onForegroundMessage(RemoteMessage message) async {
    final notification = message.notification;
    if (notification == null) return;

    if (!kIsWeb && Platform.isAndroid) {
      // 키워드 매칭 확인
      final roomId = message.data['roomId'];
      final content = message.data['content'] ?? notification.body ?? '';
      final isKeywordHit = await _checkKeywordMatch(roomId, content);

      if (isKeywordHit) {
        // 키워드 히트: 별도 강조 알림 채널로 표시
        await _localNotifs.show(
          notification.hashCode + 1000000,
          '🔔 키워드 알림: ${notification.title}',
          content,
          const NotificationDetails(
            android: AndroidNotificationDetails(
              _keywordChannelId,
              _keywordChannelName,
              icon: '@mipmap/ic_launcher',
              importance: Importance.max,
              priority: Priority.max,
              color: Color(0xFFFFC107),
            ),
          ),
        );
      } else {
        // 기존 동작 유지
        await _localNotifs.show(
          notification.hashCode,
          notification.title,
          notification.body,
          const NotificationDetails(
            android: AndroidNotificationDetails(
              _channelId,
              _channelName,
              icon: '@mipmap/ic_launcher',
              importance: Importance.high,
              priority: Priority.high,
            ),
          ),
        );
      }
    }
  }

  static Future<bool> _checkKeywordMatch(String? roomId, String content) async {
    if (roomId == null || content.isEmpty) return false;
    try {
      const storage = FlutterSecureStorage();
      final raw = await storage.read(key: 'chatflow.roomKeywords');
      if (raw == null || raw.isEmpty) return false;
      final decoded = jsonDecode(raw) as Map<String, dynamic>;
      final keywords =
          (decoded[roomId] as List?)?.map((e) => e.toString()).toList();
      if (keywords == null || keywords.isEmpty) return false;
      final lower = content.toLowerCase();
      return keywords.any((k) => lower.contains(k.toLowerCase()));
    } catch (_) {
      return false;
    }
  }
}
