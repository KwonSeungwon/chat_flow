import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/features/chat/widgets/create_room_dialog.dart';

void main() {
  testWidgets('비밀번호는 20자를 넘게 입력할 수 없다', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const CreateRoomDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('비밀방'));
    await tester.pumpAndSettle();
    final pw = find.widgetWithText(TextFormField, '비밀번호');
    await tester.enterText(pw, 'p' * 21);
    await tester.pump();

    final field = tester.widget<TextField>(
        find.descendant(of: pw, matching: find.byType(TextField)));
    expect(field.controller!.text.length, 20);
  });
}
