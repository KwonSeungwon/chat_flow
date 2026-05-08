// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:js' as js;
import 'dart:convert';

class WebUnloadHandler {
  static bool _registered = false;

  static void register({
    required String Function() jwtProvider,
    required Future<String?> Function() fcmTokenProvider,
    required String apiBaseUrl,
  }) {
    if (_registered) return;
    _registered = true;

    String? cachedFcmToken;
    fcmTokenProvider().then((t) => cachedFcmToken = t);

    html.window.onBeforeUnload.listen((_) {
      final jwt = jwtProvider();
      final fcm = cachedFcmToken;
      if (jwt.isEmpty || fcm == null || fcm.isEmpty) return;

      // fetch + keepalive: the browser is allowed to finish this in-flight
      // even after the document is gone. sendBeacon cannot carry the JWT
      // header so it isn't usable here.
      js.context.callMethod('fetch', [
        '$apiBaseUrl/api/fcm/unsubscribe-all',
        js.JsObject.jsify({
          'method': 'POST',
          'keepalive': true,
          'headers': {
            'Authorization': 'Bearer $jwt',
            'Content-Type': 'application/json',
          },
          'body': jsonEncode({'token': fcm}),
        }),
      ]);
    });
  }
}
