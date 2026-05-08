/// No-op on platforms without `dart:html` (mobile, desktop). Native FCM
/// lifecycle is handled by the OS — beforeunload only matters on the web.
class WebUnloadHandler {
  static void register({
    required String Function() jwtProvider,
    required Future<String?> Function() fcmTokenProvider,
    required String apiBaseUrl,
  }) {
    // intentionally empty
  }

  static void unregister() {
    // intentionally empty
  }
}
