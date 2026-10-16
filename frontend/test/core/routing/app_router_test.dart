import 'dart:convert';
import 'dart:typed_data';

import 'package:chatflow/core/constants/storage_keys.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/core/network/stomp_service.dart';
import 'package:chatflow/core/routing/app_router.dart';
import 'package:chatflow/features/auth/login_page.dart';
import 'package:chatflow/features/chat/chat_notifier.dart';
import 'package:chatflow/features/chat/chat_page.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Records every request path and answers 404 — no backend.
class _RecordingAdapter implements HttpClientAdapter {
  final paths = <String>[];

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    paths.add(options.path);
    return ResponseBody.fromString('{}', 404, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

/// exp가 먼 미래인 JWT 모양 토큰.
String _token() {
  String b64(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${b64({'alg': 'none'})}.${b64({'exp': 4102444800})}.sig';
}

void main() {
  late _RecordingAdapter adapter;
  late DioClient client;

  setUp(() {
    dotenv.testLoad(fileInput: '');
    SharedPreferences.setMockInitialValues({});
    adapter = _RecordingAdapter();
    client = DioClient()..dio.httpClientAdapter = adapter;
  });

  Future<GoRouter> pumpApp(WidgetTester tester, String location) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.binding.platformDispatcher.defaultRouteNameTestValue = location;
    addTearDown(tester.binding.platformDispatcher.clearDefaultRouteNameTestValue);

    late GoRouter router;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioClientProvider.overrideWithValue(client),
        // The room screen must not open a real socket.
        chatNotifierProvider.overrideWith((ref, roomId) => ChatNotifier(
              client,
              ref: ref,
              token: 'tok',
              username: 'alice',
              userId: 'u1',
              stompService: StompService(),
            )),
      ],
      child: Consumer(builder: (context, ref, _) {
        router = ref.watch(appRouterProvider);
        return MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              AuthHydrationGate(child: child ?? const SizedBox()),
        );
      }),
    ));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    return router;
  }

  testWidgets('logged-out /world goes to /login?redirect=/world without game calls',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});

    final router = await pumpApp(tester, '/world');

    expect(find.byType(LoginPage), findsOneWidget);
    final uri = router.routerDelegate.currentConfiguration.uri;
    expect(uri.path, '/login');
    expect(uri.queryParameters['redirect'], '/world');
    expect(adapter.paths.where((p) => p.startsWith('/api/game')), isEmpty);
  });

  testWidgets('logged-in refresh on /chat/<room> stays in that room',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({
      StorageKeys.token: _token(),
      StorageKeys.userId: 'u1',
      StorageKeys.username: 'alice',
    });
    // 사이드바 등 기존 레이아웃이 테스트 글꼴에서 넘칠 수 있다. 넘침만 무시한다.
    final original = FlutterError.onError;
    FlutterError.onError = (details) {
      if (details.exceptionAsString().contains('overflowed')) return;
      original?.call(details);
    };
    addTearDown(() => FlutterError.onError = original);

    final router = await pumpApp(tester, '/chat/room-1');

    expect(router.routerDelegate.currentConfiguration.uri.toString(),
        '/chat/room-1');
    expect(find.byType(LoginPage), findsNothing);
    expect(find.byType(ChatPage), findsOneWidget);
  });

  testWidgets('logged-out deep link keeps its query in redirect',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});

    final router = await pumpApp(tester, '/chat/room-1?messageId=m9');

    final uri = router.routerDelegate.currentConfiguration.uri;
    expect(uri.path, '/login');
    expect(uri.queryParameters['redirect'], '/chat/room-1?messageId=m9');
  });
}
