import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/bubbles/hover_reaction_bar.dart';

Widget _wrap(Widget child) =>
    MaterialApp(home: Scaffold(body: Center(child: child)));

void main() {
  const reactions = ['👍', '❤️', '😂'];

  group('HoverReactionBar', () {
    testWidgets('반응 이모지 렌더 + 탭 시 onReaction 호출', (tester) async {
      String? tapped;
      await tester.pumpWidget(_wrap(HoverReactionBar(
        reactions: reactions,
        onReaction: (e) => tapped = e,
      )));

      for (final e in reactions) {
        expect(find.text(e), findsOneWidget);
      }
      await tester.tap(find.text('❤️'));
      expect(tapped, '❤️');
    });

    testWidgets('onReply 제공 시 답글 버튼 노출 + 탭 시 콜백', (tester) async {
      var replied = false;
      await tester.pumpWidget(_wrap(HoverReactionBar(
        reactions: reactions,
        onReaction: (_) {},
        onReply: () => replied = true,
      )));

      expect(find.byIcon(Icons.reply), findsOneWidget);
      await tester.tap(find.byIcon(Icons.reply));
      expect(replied, isTrue);
    });

    testWidgets('onMore 제공 시 ⋯ 버튼 노출 + 탭 시 전역 좌표 전달', (tester) async {
      Offset? pos;
      await tester.pumpWidget(_wrap(HoverReactionBar(
        reactions: reactions,
        onReaction: (_) {},
        onMore: (p) => pos = p,
      )));

      expect(find.byIcon(Icons.more_horiz), findsOneWidget);
      await tester.tap(find.byIcon(Icons.more_horiz));
      expect(pos, isNotNull);
    });

    testWidgets('반응 + 답글 + ⋯ 모두 한 툴바에 노출된다', (tester) async {
      await tester.pumpWidget(_wrap(HoverReactionBar(
        reactions: reactions,
        onReaction: (_) {},
        onReply: () {},
        onMore: (_) {},
      )));

      expect(find.text('👍'), findsOneWidget);
      expect(find.byIcon(Icons.reply), findsOneWidget);
      expect(find.byIcon(Icons.more_horiz), findsOneWidget);
    });

    testWidgets('onReaction이 null이면 이모지는 숨고 액션 버튼만 남는다', (tester) async {
      await tester.pumpWidget(_wrap(HoverReactionBar(
        reactions: reactions,
        onReply: () {},
        onMore: (_) {},
      )));

      expect(find.text('👍'), findsNothing);
      expect(find.byIcon(Icons.reply), findsOneWidget);
      expect(find.byIcon(Icons.more_horiz), findsOneWidget);
    });
  });
}
