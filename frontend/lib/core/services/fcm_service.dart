import 'dart:io';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

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
      await _localNotifs
          .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>()
          ?.createNotificationChannel(
            const AndroidNotificationChannel(
              _channelId,
              _channelName,
              description: 'ChatFlow 채팅 메시지 알림',
              importance: Importance.high,
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
