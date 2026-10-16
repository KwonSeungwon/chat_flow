// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;
import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart' show visibleForTesting;

class WebUnloadHandler {
  static bool _registered = false;
  static StreamSubscription<html.Event>? _sub;
  static String? _cachedFcmToken;

  /// The FCM token the beforeunload listener will send. Exposed for tests.
  @visibleForTesting
  static String? get cachedFcmToken => _cachedFcmToken;

  static void register({
    required String Function() jwtProvider,
    required Future<String?> Function() fcmTokenProvider,
    required String apiBaseUrl,
  }) {
    // Eager cache so the synchronous beforeunload listener can read it
    // without awaiting. Refreshed on every register (startup, after login):
    // a lookup that failed or returned null earlier gets another chance.
    // A failure (e.g. Firebase not initialized) leaves the cache as is —
    // we intentionally no-op on unload in that case.
    fcmTokenProvider().then((t) {
      if (t != null) _cachedFcmToken = t;
    }).catchError((Object _) {});

    if (_registered) return;
    _registered = true;

    _sub = html.window.onBeforeUnload.listen((_) {
      final jwt = jwtProvider();
      final fcm = _cachedFcmToken;
      if (jwt.isEmpty || fcm == null || fcm.isEmpty) return;

      // fetch + keepalive: the browser is allowed to finish this in-flight
      // even after the document is gone. sendBeacon cannot carry the JWT
      // header so it isn't usable here.
      html.window.fetch(
        '$apiBaseUrl/api/fcm/unsubscribe-all',
        {
          'method': 'POST',
          'keepalive': true,
          'headers': {
            'Authorization': 'Bearer $jwt',
            'Content-Type': 'application/json',
          },
          'body': jsonEncode({'token': fcm}),
        },
      );
    });
  }

  /// Cancels the beforeunload listener. Use after explicit logout so the
  /// fetch doesn't fire (logout already calls `FcmService.deleteToken()`).
  static void unregister() {
    _sub?.cancel();
    _sub = null;
    _registered = false;
    _cachedFcmToken = null;
  }
}
