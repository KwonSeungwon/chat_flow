@TestOn('browser')
library;

import 'package:chatflow/core/services/web_unload_handler_web.dart';
import 'package:flutter_test/flutter_test.dart';

/// Web only: `flutter test --platform chrome test/core/services/web_unload_handler_test.dart`
void main() {
  tearDown(WebUnloadHandler.unregister);

  test('token lookup failing before Firebase init is swallowed', () async {
    WebUnloadHandler.register(
      jwtProvider: () => 'jwt',
      fcmTokenProvider: () async => throw StateError('no Firebase app'),
      apiBaseUrl: '',
    );
    // An unhandled async error would fail this test.
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(WebUnloadHandler.cachedFcmToken, isNull);
  });

  test('registering again (after login) refreshes the cached token', () async {
    WebUnloadHandler.register(
      jwtProvider: () => 'jwt',
      fcmTokenProvider: () async => null,
      apiBaseUrl: '',
    );
    WebUnloadHandler.register(
      jwtProvider: () => 'jwt',
      fcmTokenProvider: () async => 'fcm-token',
      apiBaseUrl: '',
    );
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(WebUnloadHandler.cachedFcmToken, 'fcm-token');
  });
}
