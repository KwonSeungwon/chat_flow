import 'dart:convert';

import 'package:chatflow/core/constants/storage_keys.dart';
import 'package:chatflow/features/auth/auth_provider.dart';
import 'package:chatflow/features/chat/chat_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// exp가 먼 미래인 JWT 모양 토큰. 저장소 hydration이 로그인 상태로 끝난다.
String _token() {
  String b64(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${b64({'alg': 'none'})}.${b64({'exp': 4102444800})}.sig';
}

Widget _app() {
  final router = GoRouter(
    initialLocation: '/chat',
    routes: [
      // 앱처럼 hydration이 끝난 뒤에 ChatPage를 띄운다.
      GoRoute(
          path: '/chat',
          builder: (_, __) => Consumer(
              builder: (_, ref, __) => ref.watch(authProvider).isHydrated
                  ? const ChatPage()
                  : const SizedBox())),
      GoRoute(path: '/login', builder: (_, __) => const Text('LOGIN')),
      GoRoute(
          path: '/world',
          builder: (_, __) => const Scaffold(body: Text('WORLD PAGE'))),
    ],
  );
  return ProviderScope(child: MaterialApp.router(routerConfig: router));
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({
      StorageKeys.token: _token(),
      StorageKeys.userId: 'u1',
      StorageKeys.username: 'alice',
    });
  });

  testWidgets('lobby "위키 던전" button opens /world', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('위키 던전'));
    await tester.pumpAndSettle();
    expect(find.text('WORLD PAGE'), findsOneWidget);
  });

  testWidgets('wide AppBar "월드" icon opens /world', (tester) async {
    tester.view.physicalSize = const Size(1280, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    // 사이드바 헤더(기존 코드)가 테스트 글꼴에서 넘친다. 넘침 경고만 무시한다.
    final original = FlutterError.onError;
    FlutterError.onError = (details) {
      if (details.exceptionAsString().contains('overflowed')) return;
      original?.call(details);
    };
    addTearDown(() => FlutterError.onError = original);

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('월드'));
    await tester.pumpAndSettle();
    expect(find.text('WORLD PAGE'), findsOneWidget);
  });
}
