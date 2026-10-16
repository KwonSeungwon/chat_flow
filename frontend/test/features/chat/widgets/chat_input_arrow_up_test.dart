import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/chat_input.dart';

Widget _wrap(Widget child) =>
    MaterialApp(home: Scaffold(body: child));

void main() {
  group('ChatInput ArrowUp edit-last-message', () {
    testWidgets('ArrowUp on empty input calls onEditLastMessage', (tester) async {
      var called = false;

      await tester.pumpWidget(_wrap(
        ChatInput(
          isConnected: true,
          onSend: (_, {String priority = 'ROUTINE'}) {},
          onEditLastMessage: () => called = true,
        ),
      ));

      // Focus the text field
      await tester.tap(find.byType(TextField));
      await tester.pump();

      // Press ArrowUp
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pump();

      expect(called, isTrue);
    });

    testWidgets('ArrowUp with text in input does NOT call onEditLastMessage', (tester) async {
      var called = false;

      await tester.pumpWidget(_wrap(
        ChatInput(
          isConnected: true,
          onSend: (_, {String priority = 'ROUTINE'}) {},
          onEditLastMessage: () => called = true,
        ),
      ));

      // Focus and type some text
      await tester.tap(find.byType(TextField));
      await tester.pump();
      await tester.enterText(find.byType(TextField), 'hello');
      await tester.pump();

      // Press ArrowUp
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pump();

      expect(called, isFalse);
    });

    testWidgets('ArrowUp with null callback does not crash', (tester) async {
      await tester.pumpWidget(_wrap(
        ChatInput(
          isConnected: true,
          onSend: (_, {String priority = 'ROUTINE'}) {},
          onEditLastMessage: null,
        ),
      ));

      // Focus the text field
      await tester.tap(find.byType(TextField));
      await tester.pump();

      // Press ArrowUp — should not throw
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pump();

      // No crash = pass
    });

    testWidgets('ArrowUp with Shift held does NOT call onEditLastMessage', (tester) async {
      var called = false;

      await tester.pumpWidget(_wrap(
        ChatInput(
          isConnected: true,
          onSend: (_, {String priority = 'ROUTINE'}) {},
          onEditLastMessage: () => called = true,
        ),
      ));

      await tester.tap(find.byType(TextField));
      await tester.pump();

      // Press Shift+ArrowUp
      await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
      await tester.pump();

      expect(called, isFalse);
    });
  });
}
