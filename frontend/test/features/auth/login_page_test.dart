import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:gossiplog/core/network/dio_client.dart';
import 'package:gossiplog/features/auth/auth_provider.dart';
import 'package:gossiplog/features/auth/login_page.dart';

/// register 호출 인자를 기록하는 가짜. 네트워크는 타지 않는다.
/// `role`은 일부러 nullable·기본값 없음으로 받는다 — 화면이 role을 넘기지 않으면 null이 남는다.
class _RecordingAuth extends AuthNotifier {
  _RecordingAuth(super.dioClient);

  int registerCalls = 0;
  String? registeredUsername;
  String? sentRole;

  @override
  Future<void> register(String username, String password, {String? role}) async {
    registerCalls++;
    registeredUsername = username;
    sentRole = role;
  }
}

void main() {
  late _RecordingAuth auth;

  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  Future<void> pump(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const LoginPage()),
    ]);
    addTearDown(router.dispose);

    auth = _RecordingAuth(DioClient());
    await tester.pumpWidget(ProviderScope(
      overrides: [authProvider.overrideWith((ref) => auth)],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('로그인/회원가입 탭을 오가면 폼이 바뀐다', (tester) async {
    await pump(tester, const Size(1280, 900));

    expect(find.text('Gossiplog'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, '들어가기'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '비밀번호 확인'), findsNothing);

    await tester.tap(find.text('회원가입').first);
    await tester.pumpAndSettle();
    expect(find.widgetWithText(TextFormField, '비밀번호 확인'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, '회원가입'), findsOneWidget);

    await tester.tap(find.text('로그인'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(TextFormField, '비밀번호 확인'), findsNothing);
    expect(find.widgetWithText(FilledButton, '들어가기'), findsOneWidget);
  });

  testWidgets('회원가입에 직군 선택이 없다', (tester) async {
    await pump(tester, const Size(1280, 900));
    await tester.tap(find.text('회원가입').first);
    await tester.pumpAndSettle();

    expect(find.byType(DropdownButtonFormField<String>), findsNothing);
    expect(find.text('역할'), findsNothing);
    expect(find.textContaining('간호사'), findsNothing);
    expect(find.byIcon(Icons.local_hospital), findsNothing);
  });

  testWidgets('가입할 때 role을 보내지 않는다', (tester) async {
    await pump(tester, const Size(1280, 900));
    await tester.tap(find.text('회원가입').first);
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextFormField, '아이디'), 'newbie');
    await tester.enterText(find.widgetWithText(TextFormField, '비밀번호'), 'Passw0rd!');
    await tester.enterText(find.widgetWithText(TextFormField, '비밀번호 확인'), 'Passw0rd!');
    await tester.tap(find.widgetWithText(FilledButton, '회원가입'));
    await tester.pumpAndSettle();

    expect(auth.registerCalls, 1);
    expect(auth.registeredUsername, 'newbie');
    expect(auth.sentRole, isNull);
  });

  for (final register in [false, true]) {
    testWidgets('좁은 폭(390)에서 ${register ? '회원가입' : '로그인'} 탭이 넘치지 않는다',
        (tester) async {
      await pump(tester, const Size(390, 844));
      if (register) {
        await tester.tap(find.text('회원가입').first);
        await tester.pumpAndSettle();
        // 검증 오류 문구까지 펼친 가장 긴 상태
        await tester.ensureVisible(find.widgetWithText(FilledButton, '회원가입'));
        await tester.tap(find.widgetWithText(FilledButton, '회원가입'));
        await tester.pumpAndSettle();
        expect(find.text('아이디는 2자 이상으로 해 주세요'), findsOneWidget);
      }
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('가로로 누운 폰(844x390)에서 아이디·비밀번호·버튼이 스크롤 없이 보인다',
      (tester) async {
    await pump(tester, const Size(844, 390));
    final id = tester.getRect(find.widgetWithText(TextFormField, '아이디'));
    final button = tester.getRect(find.widgetWithText(FilledButton, '들어가기'));
    expect(id.top, greaterThanOrEqualTo(0));
    expect(button.bottom, lessThanOrEqualTo(390));
    // 히어로와 폼이 나란히 놓인다.
    expect(tester.getRect(find.text('Gossiplog')).right, lessThan(id.left));
    expect(tester.takeException(), isNull);
  });

  for (final size in const [Size(360, 640), Size(844, 390), Size(1920, 1080)]) {
    for (final register in [false, true]) {
      testWidgets(
          '${size.width.toInt()}x${size.height.toInt()} '
          '${register ? '회원가입' : '로그인'}에서 넘치지 않는다', (tester) async {
        await pump(tester, size);
        if (register) {
          await tester.tap(find.text('회원가입').first);
          await tester.pumpAndSettle();
        }
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('회원가입 아이디는 서버 규칙(2~20자, 영문·숫자·한글·_.-)을 따른다',
      (tester) async {
    await pump(tester, const Size(1280, 900));
    await tester.tap(find.text('회원가입').first);
    await tester.pumpAndSettle();
    final id = find.widgetWithText(TextFormField, '아이디');
    await tester.enterText(find.widgetWithText(TextFormField, '비밀번호'), 'Passw0rd!');
    await tester.enterText(find.widgetWithText(TextFormField, '비밀번호 확인'), 'Passw0rd!');

    Future<void> submit(String name) async {
      await tester.enterText(id, name);
      await tester.tap(find.widgetWithText(FilledButton, '회원가입'));
      await tester.pumpAndSettle();
    }

    await submit('a' * 21);
    expect(find.text('아이디는 20자 이하로 해 주세요'), findsOneWidget);
    await submit('bad name!');
    expect(find.text('아이디는 영문, 숫자, 한글, _ . - 만 쓸 수 있어요'), findsOneWidget);
    expect(auth.registerCalls, 0);

    await submit('  새_user.1-  ');
    expect(auth.registerCalls, 1);
    expect(auth.registeredUsername, '새_user.1-');
  });

  testWidgets('로그인 탭은 기존 아이디 규칙만 본다(옛 계정 이름 허용)', (tester) async {
    await pump(tester, const Size(1280, 900));
    await tester.enterText(
        find.widgetWithText(TextFormField, '아이디'), 'Forward Tester');
    await tester.enterText(find.widgetWithText(TextFormField, '비밀번호'), 'Passw0rd!');
    await tester.tap(find.widgetWithText(FilledButton, '들어가기'));
    await tester.pumpAndSettle();

    expect(find.text('아이디는 영문, 숫자, 한글, _ . - 만 쓸 수 있어요'), findsNothing);
  });
}
