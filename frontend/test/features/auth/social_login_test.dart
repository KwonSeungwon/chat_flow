// ignore_for_file: deprecated_member_use
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/core/constants/storage_keys.dart';
import 'package:gossiplog/core/network/dio_client.dart';
import 'package:gossiplog/core/routing/app_router.dart';
import 'package:gossiplog/features/auth/auth_provider.dart';
import 'package:gossiplog/features/auth/login_page.dart';
import 'package:gossiplog/features/auth/social_login_buttons.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

String _token() {
  String b64(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${b64({'alg': 'none'})}.${b64({'exp': 4102444800})}.sig';
}

/// /api/auth/social/exchange: code "good"이면 세션을, 아니면 400을 준다. 나머지는 404.
class _ExchangeAdapter implements HttpClientAdapter {
  final exchanged = <String>[];

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    if (options.path == '/api/auth/social/exchange') {
      final code = (options.data as Map)['code'] as String;
      exchanged.add(code);
      if (code == 'good') {
        return ResponseBody.fromString(
            jsonEncode({'token': _token(), 'userId': 'u9', 'username': '홍길동', 'profileImageUrl': null}),
            200,
            headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
      }
      return ResponseBody.fromString('', 400);
    }
    return ResponseBody.fromString('{}', 404, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _ExchangeAdapter adapter;
  late DioClient client;

  setUp(() {
    dotenv.testLoad(fileInput: '');
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
    adapter = _ExchangeAdapter();
    client = DioClient()..dio.httpClientAdapter = adapter;
  });

  test('loginWithSocialCode는 받은 세션을 일반 로그인과 같은 키로 저장한다', () async {
    final n = AuthNotifier(client);
    addTearDown(n.dispose);

    await n.loginWithSocialCode('good');

    expect(n.state.isAuthenticated, isTrue);
    expect(n.state.username, '홍길동');
    const s = FlutterSecureStorage();
    expect(await s.read(key: StorageKeys.userId), 'u9');
    expect(await s.read(key: StorageKeys.username), '홍길동');
    expect(await s.read(key: StorageKeys.token), isNotNull);
  });

  test('만료되거나 이미 쓴 코드는 로그인하지 않고 안내 문구를 남긴다', () async {
    final n = AuthNotifier(client);
    addTearDown(n.dispose);

    await n.loginWithSocialCode('used');

    expect(n.state.isAuthenticated, isFalse);
    expect(n.state.error, '소셜 로그인이 만료됐어요. 다시 시도해 주세요.');
  });

  testWidgets('소셜 버튼은 제공자별 시작 주소를 넘긴다', (tester) async {
    final started = <String>[];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SocialLoginButtons(onStart: started.add)),
    ));

    await tester.tap(find.text('카카오로 시작하기'));
    await tester.tap(find.text('네이버로 시작하기'));
    await tester.tap(find.text('Google 계정으로 계속하기'));

    expect(started, [
      '/oauth2/authorization/kakao',
      '/oauth2/authorization/naver',
      '/oauth2/authorization/google',
    ]);
  });

  for (final width in [400.0, 216.0]) {
    testWidgets('버튼마다 제공자 로고가 글자 앞에 붙고 겹치지 않는다 (버튼 폭 $width)', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(width: width, child: SocialLoginButtons(onStart: (_) {})),
          ),
        ),
      ));

      for (final (id, text) in [
        ('kakao', '카카오로 시작하기'),
        ('naver', '네이버로 시작하기'),
        ('google', 'Google 계정으로 계속하기'),
      ]) {
        final logo = find.byKey(ValueKey('social-logo-$id'));
        final button = find.ancestor(of: logo, matching: find.byType(OutlinedButton));
        expect(button, findsOneWidget, reason: '$id 로고가 버튼 안에 있다');
        expect(tester.getRect(logo).right + 10,
            lessThanOrEqualTo(tester.getRect(find.text(text)).left + 0.5),
            reason: '$id 로고와 글자 사이 10px');
      }
      expect(tester.takeException(), isNull);
    });
  }

  Future<GoRouter> pumpApp(WidgetTester tester, String location) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    tester.binding.platformDispatcher.defaultRouteNameTestValue = location;
    addTearDown(tester.binding.platformDispatcher.clearDefaultRouteNameTestValue);
    final original = FlutterError.onError;
    FlutterError.onError = (details) {
      if (details.exceptionAsString().contains('overflowed')) return;
      original?.call(details);
    };
    addTearDown(() => FlutterError.onError = original);

    late GoRouter router;
    await tester.pumpWidget(ProviderScope(
      overrides: [dioClientProvider.overrideWithValue(client)],
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

  testWidgets('로그아웃 상태의 /social-callback은 코드를 바꾼 뒤 /chat으로 간다', (tester) async {
    final router = await pumpApp(tester, '/social-callback?code=good');

    expect(adapter.exchanged, ['good']);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/chat');
  });

  testWidgets('코드 교환에 실패하면 로그인 화면에 실패를 알린다', (tester) async {
    final router = await pumpApp(tester, '/social-callback?code=used');

    expect(router.routerDelegate.currentConfiguration.uri.path, '/login');
    expect(find.byType(LoginPage), findsOneWidget);
    expect(find.text('소셜 로그인이 만료됐어요. 다시 시도해 주세요.'), findsOneWidget);
  });

  testWidgets('제공자 단계에서 실패해 돌아오면(socialError) 로그인 화면에 알린다', (tester) async {
    await pumpApp(tester, '/login?socialError=1');

    expect(find.text('소셜 로그인에 실패했어요. 다시 시도해 주세요.'), findsOneWidget);
  });
}
