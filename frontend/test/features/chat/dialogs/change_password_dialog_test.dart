import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/dialogs/change_password_dialog.dart';

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
}
