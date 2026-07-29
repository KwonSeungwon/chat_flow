import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/login_page.dart';

/// 회원가입이 실패했으면 실패 이유가 화면에 남아 있어야 한다.
///
/// 회귀 대상(2026-07-29 prod 재현): 프로필 이미지를 고른 채로 가입에 실패하면
/// 성공 여부를 확인하지 않은 아바타 업로드가 인증 없이 나가 401을 받고,
/// 그 401이 authProvider state를 리셋해 방금 띄운 에러 배너까지 지웠다.
/// 사용자에겐 "회원가입 버튼이 아무 반응 없는" 화면으로 보였다.
class _RegisterFailsAdapter implements HttpClientAdapter {
  final List<String> requestedPaths = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requestedPaths.add(options.path);
    // 아이디 중복 → 서버는 본문 없는 400을 준다(AuthController.register).
    final status = options.path == '/api/auth/register' ? 400 : 401;
    return ResponseBody.fromString('{}', status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

/// 가입이 성공하는 세상. 새 가드가 정상 경로까지 막아버리지 않는지 확인하는 용도.
class _RegisterSucceedsAdapter implements HttpClientAdapter {
  final List<String> requestedPaths = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requestedPaths.add(options.path);
    final body = switch (options.path) {
      '/api/auth/register' => '{"token":"t","userId":"u1","role":"NURSE"}',
      '/api/files/upload' => '{"url":"https://cdn.example/avatar.png"}',
      _ => '{}',
    };
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

/// 실제로 디코딩되는 1x1 투명 PNG — 가짜 바이트를 쓰면 CircleAvatar가
/// "Invalid image data"를 던져 테스트 로그를 오염시킨다.
final _onePixelPng = base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGA'
    'hKmMIQAAAABJRU5ErkJggg==');

class _FakeFilePicker extends FilePicker with MockPlatformInterfaceMixin {
  @override
  Future<FilePickerResult?> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    bool allowCompression = true,
    int compressionQuality = 30,
    bool allowMultiple = false,
    bool withData = false,
    bool withReadStream = false,
    bool lockParentWindow = false,
    bool readSequential = false,
  }) async =>
      FilePickerResult([
        PlatformFile(
          name: 'avatar.png',
          size: _onePixelPng.length,
          bytes: _onePixelPng,
        ),
      ]);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _RegisterFailsAdapter adapter;
  late DioClient client;

  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    // 되돌리지 않는다 — FilePicker.platform 게터는 late 필드라 플랫폼 등록이 없는
    // 테스트에선 읽는 순간 던진다. 원본을 캡처할 방법이 없다(파일당 isolate라 무해).
    FilePicker.platform = _FakeFilePicker();
    adapter = _RegisterFailsAdapter();
    client = DioClient()..dio.httpClientAdapter = adapter;
  });

  /// LoginPage는 가입 성공 시 GoRouterState.of(context)를 읽으므로 라우터가 있어야
  /// 성공 경로를 태울 수 있다. 실패 경로는 라우터 없이도 돌지만 하나로 통일한다.
  Future<void> pumpLoginPage(WidgetTester tester, {DioClient? dioClient}) async {
    // 기본 800x600 뷰포트에선 회원가입 버튼이 화면 밖(y≈684)이라 탭이 빗나간다.
    tester.view.physicalSize = const Size(1000, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    final router = GoRouter(
      routes: [
        GoRoute(path: '/', builder: (_, __) => const LoginPage()),
        GoRoute(
            path: '/chat',
            builder: (_, __) => const Scaffold(body: Text('CHAT'))),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [dioClientProvider.overrideWithValue(dioClient ?? client)],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
  }

  /// 회원가입 탭으로 전환하고 폼을 채운다.
  Future<void> fillRegisterForm(WidgetTester tester) async {
    await tester.tap(find.text('회원가입').first);
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextFormField, '아이디'), 'taken');
    await tester.enterText(
        find.widgetWithText(TextFormField, '비밀번호'), 'Passw0rd!');
    await tester.enterText(
        find.widgetWithText(TextFormField, '비밀번호 확인'), 'Passw0rd!');
    await tester.pumpAndSettle();
  }

  /// 폼 위쪽 원형 아바타(GestureDetector로 감싼 CircleAvatar)를 눌러 이미지를 고른다.
  Future<void> pickAvatar(WidgetTester tester) async {
    await tester.tap(find.ancestor(
      of: find.byType(CircleAvatar),
      matching: find.byType(GestureDetector),
    ));
    await tester.pumpAndSettle();
    final avatar = tester.widget<CircleAvatar>(find.byType(CircleAvatar));
    expect(avatar.backgroundImage, isNotNull,
        reason: '아바타 이미지가 실제로 선택되어야 이 회귀 테스트가 의미를 가진다');
  }

  Future<void> submit(WidgetTester tester) async {
    await tester.tap(find.widgetWithText(FilledButton, '회원가입'));
    await tester.pumpAndSettle();
  }

  group('회원가입 실패 시 에러 표시', () {
    testWidgets('프로필 이미지를 고르지 않았을 때 에러가 보인다 — 기준선', (tester) async {
      await pumpLoginPage(tester);
      await fillRegisterForm(tester);
      await submit(tester);

      expect(find.text('이미 사용 중인 아이디입니다.'), findsOneWidget);
    });

    testWidgets('프로필 이미지를 골랐어도 에러가 그대로 보인다', (tester) async {
      await pumpLoginPage(tester);
      await fillRegisterForm(tester);

      // 아바타 피커 — 폼 위쪽의 원형 버튼.
      await pickAvatar(tester);

      await submit(tester);

      expect(find.text('이미 사용 중인 아이디입니다.'), findsOneWidget);
    });

    testWidgets('가입에 실패했으면 아바타 업로드를 시도하지 않는다', (tester) async {
      await pumpLoginPage(tester);
      await fillRegisterForm(tester);
      await pickAvatar(tester);

      await submit(tester);

      expect(adapter.requestedPaths, contains('/api/auth/register'));
      expect(adapter.requestedPaths, isNot(contains('/api/files/upload')));
    });

    testWidgets('가입에 성공하면 아바타는 정상적으로 업로드된다 — 가드가 정상 경로를 막지 않는다',
        (tester) async {
      final okAdapter = _RegisterSucceedsAdapter();
      await pumpLoginPage(tester,
          dioClient: DioClient()..dio.httpClientAdapter = okAdapter);
      await fillRegisterForm(tester);
      await pickAvatar(tester);

      await submit(tester);

      expect(okAdapter.requestedPaths, containsAllInOrder(
          ['/api/auth/register', '/api/files/upload', '/api/auth/profile']));
      expect(find.text('CHAT'), findsOneWidget, reason: '가입 성공 → /chat 이동');
    });
  });
}
