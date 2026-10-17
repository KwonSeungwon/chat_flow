import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';
import 'package:chatflow/features/chat/dialogs/change_password_dialog.dart';

class _ReloginAuth extends AuthNotifier {
  _ReloginAuth() : super(DioClient());

  @override
  Future<PasswordChangeResult> changePassword(
          String currentPassword, String newPassword) async =>
      PasswordChangeResult.reloginRequired;
}

final _navKey = GlobalKey<NavigatorState>();

/// Mimics the router redirect: the dialog is torn down while the call runs.
class _UnmountingAuth extends AuthNotifier {
  _UnmountingAuth() : super(DioClient());

  @override
  Future<PasswordChangeResult> changePassword(
      String currentPassword, String newPassword) async {
    _navKey.currentState!.pop();
    await Future<void>.delayed(const Duration(milliseconds: 10));
    return PasswordChangeResult.reloginRequired;
  }
}

void main() {
  testWidgets('비밀번호 3개 입력 필드가 표시된다', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('current_pw')), findsOneWidget);
    expect(find.byKey(const Key('new_pw')), findsOneWidget);
    expect(find.byKey(const Key('confirm_pw')), findsOneWidget);
    expect(find.text('취소'), findsOneWidget);
    expect(find.text('변경'), findsOneWidget);
  });

  testWidgets('새 비밀번호 불일치 시 에러 메시지 표시', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('current_pw')), 'old123');
    await tester.enterText(find.byKey(const Key('new_pw')), 'new12345');
    await tester.enterText(find.byKey(const Key('confirm_pw')), 'different');
    await tester.tap(find.text('변경'));
    await tester.pump();

    expect(find.text('새 비밀번호가 일치하지 않습니다.'), findsOneWidget);
  });

  testWidgets('새 비밀번호 8자 미만 시 에러 메시지 표시', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('current_pw')), 'old123');
    await tester.enterText(find.byKey(const Key('new_pw')), 'short');
    await tester.enterText(find.byKey(const Key('confirm_pw')), 'short');
    await tester.tap(find.text('변경'));
    await tester.pump();

    expect(find.text('비밀번호는 8자 이상이어야 합니다.'), findsOneWidget);
  });

  testWidgets('새 토큰 저장 실패면 성공 안내와 재로그인 요청을 띄운다', (tester) async {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    await tester.pumpWidget(ProviderScope(
      overrides: [authProvider.overrideWith((_) => _ReloginAuth())],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('current_pw')), 'old12345');
    await tester.enterText(find.byKey(const Key('new_pw')), 'new12345');
    await tester.enterText(find.byKey(const Key('confirm_pw')), 'new12345');
    await tester.tap(find.text('변경'));
    await tester.pumpAndSettle();

    expect(find.byType(ChangePasswordDialog), findsNothing);
    expect(find.text('비밀번호가 변경되었습니다. 다시 로그인해 주세요.'), findsOneWidget);
  });

  testWidgets('재로그인 안내는 다이얼로그가 먼저 사라져도 보인다', (tester) async {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    await tester.pumpWidget(ProviderScope(
      overrides: [authProvider.overrideWith((_) => _UnmountingAuth())],
      child: MaterialApp(
        navigatorKey: _navKey,
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('current_pw')), 'old12345');
    await tester.enterText(find.byKey(const Key('new_pw')), 'new12345');
    await tester.enterText(find.byKey(const Key('confirm_pw')), 'new12345');
    await tester.tap(find.text('변경'));
    // pumpAndSettle would outlast the snackbar's auto-dismiss.
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }

    expect(find.byType(ChangePasswordDialog), findsNothing);
    expect(find.text('비밀번호가 변경되었습니다. 다시 로그인해 주세요.'), findsOneWidget);
  });
}
