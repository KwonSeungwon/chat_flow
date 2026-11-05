import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/features/chat/dialogs/room_settings_dialog.dart';
import 'package:gossiplog/shared/models/chat_room.dart';

Future<void> _open(WidgetTester tester) async {
  final room = ChatRoom(id: 'r1', name: '원래 이름', participantCount: 1);
  await tester.pumpWidget(ProviderScope(
    child: MaterialApp(
      home: Scaffold(
        body: Consumer(builder: (ctx, ref, _) => TextButton(
          onPressed: () => showRoomSettingsDialog(ctx, ref, 'r1', room),
          child: const Text('open'),
        )),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('이름을 비우고 저장하면 오류를 보이고 창을 닫지 않는다', (tester) async {
    await _open(tester);

    await tester.enterText(find.widgetWithText(TextFormField, '채팅방 이름'), '  ');
    await tester.tap(find.text('저장'));
    await tester.pump();

    expect(find.text('이름은 2자 이상이어야 합니다'), findsOneWidget);
    expect(find.text('채팅방 설정'), findsOneWidget);
  });

  testWidgets('설명이 200자를 넘으면 입력이 200자에서 멈춘다', (tester) async {
    await _open(tester);

    final desc = find.widgetWithText(TextFormField, '설명');
    await tester.enterText(desc, 'd' * 201);
    await tester.pump();

    final field = tester.widget<TextField>(
        find.descendant(of: desc, matching: find.byType(TextField)));
    expect(field.controller!.text.length, 200);
  });
}
