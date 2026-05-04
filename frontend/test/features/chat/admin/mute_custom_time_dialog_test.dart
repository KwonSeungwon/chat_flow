import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:chatflow/features/chat/admin/widgets/mute_custom_time_dialog.dart';

void main() {
  Future<void> openDialog(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(builder: (ctx) => ElevatedButton(
          onPressed: () => showMuteCustomTimeDialog(ctx),
          child: const Text('open'),
        )),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('valid input 12 returns 12', (tester) async {
    int? result;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(builder: (ctx) => ElevatedButton(
          onPressed: () async {
            result = await showMuteCustomTimeDialog(ctx);
          },
          child: const Text('open'),
        )),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), '12');
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();

    expect(result, 12);
  });

  testWidgets('input 0 shows error and does not close', (tester) async {
    await openDialog(tester);
    await tester.enterText(find.byType(TextField), '0');
    await tester.tap(find.text('확인'));
    await tester.pump();
    expect(find.textContaining('1~1440분'), findsOneWidget);
  });

  testWidgets('input 1441 shows error', (tester) async {
    await openDialog(tester);
    await tester.enterText(find.byType(TextField), '1441');
    await tester.tap(find.text('확인'));
    await tester.pump();
    expect(find.textContaining('1~1440분'), findsOneWidget);
  });

  testWidgets('cancel returns null', (tester) async {
    int? result = -1;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(builder: (ctx) => ElevatedButton(
          onPressed: () async {
            result = await showMuteCustomTimeDialog(ctx);
          },
          child: const Text('open'),
        )),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();
    expect(result, isNull);
  });
}
