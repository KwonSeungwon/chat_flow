// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;
import 'dart:async';
import 'dart:convert';

class WebUnloadHandler {
  static bool _registered = false;
  static StreamSubscription<html.Event>? _sub;

  static void register({
    required String Function() jwtProvider,
    required Future<String?> Function() fcmTokenProvider,
    required String apiBaseUrl,
  }) {
    if (_registered) return;
    _registered = true;

    String? cachedFcmToken;
    // Eager cache so the synchronous beforeunload listener can read it
    // without awaiting. Null means Firebase hasn't initialized yet — we
    // intentionally no-op in that case (the OS lifecycle will clean up
    // any subscription on a fresh tab).
    fcmTokenProvider().then((t) => cachedFcmToken = t);

    _sub = html.window.onBeforeUnload.listen((_) {
      final jwt = jwtProvider();
      final fcm = cachedFcmToken;
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
  }
}
