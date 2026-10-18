import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../features/chat/room_keywords_provider.dart';
import '../constants/storage_keys.dart';

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

  static const _channelId = 'gossiplog_messages';
  static const _channelName = 'Gossiplog 메시지';

  static const _keywordChannelId = 'gossiplog_keywords';
  static const _keywordChannelName = 'Gossiplog 키워드 알림';

  static final _messaging = FirebaseMessaging.instance;
  static final _localNotifs = FlutterLocalNotificationsPlugin();

  /// Rooms whose topic this device's token is subscribed to (this session).
  /// A refreshed token is not subscribed anywhere, so these are replayed.
  static final Set<String> _subscribedRoomIds = {};

  static String? Function()? _activeRoomId;

  /// Records a successful room-topic subscribe/unsubscribe.
  static void noteRoomSubscription(String roomId, {required bool subscribed}) {
    if (subscribed) {
      _subscribedRoomIds.add(roomId);
    } else {
      _subscribedRoomIds.remove(roomId);
    }
  }

  /// [activeRoomId] is the room on screen: its pushes are not shown in the
  /// foreground. [dio] is the authenticated client used to resubscribe
  /// rooms when the token rotates.
  static Future<void> initialize({
    required String? Function() activeRoomId,
    required Dio Function() dio,
  }) async {
    _activeRoomId = activeRoomId;
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
      final androidPlugin = _localNotifs.resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();
      await androidPlugin?.createNotificationChannel(
        const AndroidNotificationChannel(
          _channelId,
          _channelName,
          description: 'Gossiplog 채팅 메시지 알림',
          importance: Importance.high,
        ),
      );
      await androidPlugin?.createNotificationChannel(
        const AndroidNotificationChannel(
          _keywordChannelId,
          _keywordChannelName,
          description: 'Gossiplog 키워드 매칭 알림',
          importance: Importance.max,
        ),
      );
    }

    // Register background handler before any other Firebase call
    FirebaseMessaging.onBackgroundMessage(_onBackgroundMessage);

    // Show notification while app is in foreground
    FirebaseMessaging.onMessage.listen(_onForegroundMessage);

    // A rotated token has no topic subscriptions: replay this session's.
    _messaging.onTokenRefresh
        .listen((token) => _resubscribeRooms(dio(), token));

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

  /// Invalidates the current FCM token. Subsequent `getToken()` will issue
  /// a fresh one. Firebase auto-evicts the old token from any topic
  /// subscriptions on the next push attempt that returns INVALID_REGISTRATION
  /// — so calling this on logout effectively stops all pushes for the
  /// previously logged-in user without us having to enumerate every room
  /// subscription server-side.
  static Future<void> deleteToken() async {
    _subscribedRoomIds.clear();
    try {
      await _messaging.deleteToken();
    } catch (e) {
      debugPrint('[FCM] deleteToken failed: $e');
    }
  }

  /// Listen for token refreshes (device token can change).
  static Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  // ---------------------------------------------------------------------------

  static Future<void> _resubscribeRooms(Dio dio, String token) async {
    for (final roomId in List.of(_subscribedRoomIds)) {
      try {
        await dio.post('/api/fcm/subscribe',
            data: {'token': token, 'roomId': roomId});
      } catch (e) {
        debugPrint('[FCM] resubscribe $roomId failed: $e');
      }
    }
  }

  static Future<void> _onForegroundMessage(RemoteMessage message) async {
    final notification = message.notification;
    if (notification == null) return;

    final roomId = message.data['roomId'];
    // The user is looking at this room already.
    if (roomId != null && roomId == _activeRoomId?.call()) return;

    if (!kIsWeb && Platform.isAndroid) {
      // 키워드 매칭 확인
      final content = message.data['content'] ?? notification.body ?? '';
      final isKeywordHit = await checkKeywordMatch(roomId, content);

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

  /// Whether [content] hits one of the signed-in user's keywords for
  /// [roomId]. No signed-in user → no keyword filter.
  @visibleForTesting
  static Future<bool> checkKeywordMatch(String? roomId, String content) async {
    if (roomId == null || content.isEmpty) return false;
    try {
      const storage = FlutterSecureStorage();
      final userId = await storage.read(key: StorageKeys.userId);
      if (userId == null || userId.isEmpty) return false;
      final raw =
          await storage.read(key: RoomKeywordsNotifier.storageKeyFor(userId));
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
