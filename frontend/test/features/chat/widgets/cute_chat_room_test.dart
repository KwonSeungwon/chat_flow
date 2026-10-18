import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:gossiplog/core/theme/app_theme.dart';
import 'package:gossiplog/features/chat/widgets/bubbles/avatar.dart';
import 'package:gossiplog/features/chat/widgets/bubbles/chat_bubble.dart';
import 'package:gossiplog/features/chat/widgets/chat_input.dart';
import 'package:gossiplog/features/chat/widgets/chat_messages_list.dart';
import 'package:gossiplog/shared/models/chat_message.dart';

/// 파스텔 카카오톡형 채팅방의 모양 계약.
void main() {
  ChatMessage msg(String id, String user, String content, DateTime at) =>
      ChatMessage(
        messageId: id,
        chatRoomId: 'r1',
        userId: '$user-id',
        username: user,
        content: content,
        timestamp: at.toUtc().toIso8601String(),
        type: 'CHAT',
      );

  /// 말풍선 본문 Container — 본문 텍스트를 감싼 가장 가까운 BoxDecoration.
  BoxDecoration bubbleDecorationOf(WidgetTester tester, String text) {
    final container = tester
        .widgetList<Container>(
            find.ancestor(of: find.text(text), matching: find.byType(Container)))
        .firstWhere((c) => c.decoration is BoxDecoration);
    return container.decoration! as BoxDecoration;
  }

  Widget host(Widget child, {ThemeData? theme}) => MaterialApp(
        theme: theme,
        home: Scaffold(body: child),
      );

  group('말풍선 색', () {
    testWidgets('내 말풍선은 버터 노랑, 상대는 흰색 (라이트)', (tester) async {
      final now = DateTime(2026, 10, 16, 10);
      await tester.pumpWidget(host(Column(children: [
        ChatBubble(msg: msg('a', 'me', '내 메시지', now), isMine: true, time: '10:00'),
        ChatBubble(msg: msg('b', 'bob', '상대 메시지', now), isMine: false, time: '10:00'),
      ])));

      expect(bubbleDecorationOf(tester, '내 메시지').color, AppColors.myBubble);
      expect(bubbleDecorationOf(tester, '상대 메시지').color, ChatPalette.light.otherBubble);
    });

    testWidgets('다크에서도 내 말풍선은 같은 노랑, 상대는 남색', (tester) async {
      final now = DateTime(2026, 10, 16, 10);
      await tester.pumpWidget(host(
        Column(children: [
          ChatBubble(msg: msg('a', 'me', '내 메시지', now), isMine: true, time: '10:00'),
          ChatBubble(msg: msg('b', 'bob', '상대 메시지', now), isMine: false, time: '10:00'),
        ]),
        theme: ThemeData.dark().copyWith(extensions: const [ChatPalette.dark]),
      ));

      expect(bubbleDecorationOf(tester, '내 메시지').color, AppColors.myBubble);
      expect(bubbleDecorationOf(tester, '상대 메시지').color, ChatPalette.dark.otherBubble);
    });

    test('꼬리 모서리는 그룹 첫 말풍선의 꼬리 쪽 위에만 있다', () {
      final mineFirst = bubbleRadius(isMine: true, isFirstInGroup: true);
      expect(mineFirst.topRight, const Radius.circular(6));
      expect(mineFirst.topLeft, const Radius.circular(20));
      final otherFirst = bubbleRadius(isMine: false, isFirstInGroup: true);
      expect(otherFirst.topLeft, const Radius.circular(6));
      expect(otherFirst.topRight, const Radius.circular(20));
      final follow = bubbleRadius(isMine: false, isFirstInGroup: false);
      expect(follow, BorderRadius.circular(20));
    });
  });

  testWidgets('상대 연속 메시지는 그룹 첫 줄에만 아바타·이름이 보인다', (tester) async {
    final t = DateTime.now().subtract(const Duration(minutes: 5));
    await tester.pumpWidget(host(ChatMessagesList(
      currentUsername: 'me',
      messages: [
        msg('1', 'bob', '첫 줄', t),
        msg('2', 'bob', '둘째 줄', t),
        msg('3', 'bob', '셋째 줄', t),
        msg('4', 'me', '내 답장', t),
      ],
    )));
    await tester.pump();

    expect(find.text('첫 줄'), findsOneWidget);
    expect(find.text('셋째 줄'), findsOneWidget);
    expect(find.byType(Avatar), findsOneWidget);
    expect(find.text('bob'), findsOneWidget);
  });

  testWidgets('삭제된 메시지도 그룹 연속 줄이면 아바타 없이 자리만 비운다', (tester) async {
    final now = DateTime(2026, 10, 16, 10);
    final deleted = ChatMessage(
      messageId: 'd', chatRoomId: 'r1', userId: 'bob-id', username: 'bob',
      content: 'x', timestamp: now.toUtc().toIso8601String(), type: 'CHAT',
      deleted: true,
    );
    await tester.pumpWidget(host(Column(children: [
      ChatBubble(msg: deleted, isMine: false, time: '10:00', showAvatar: false),
    ])));
    expect(find.text('삭제된 메시지입니다.'), findsOneWidget);
    expect(find.byType(Avatar), findsNothing);

    await tester.pumpWidget(host(Column(children: [
      ChatBubble(msg: deleted, isMine: false, time: '10:00'),
    ])));
    expect(find.byType(Avatar), findsOneWidget);
  });

  testWidgets('400px 폭에서 메시지 영역과 입력창이 넘치지 않는다', (tester) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final t = DateTime.now().subtract(const Duration(minutes: 5));
    final long = '아주 긴 메시지 ' * 30;
    await tester.pumpWidget(host(Column(children: [
      Expanded(
        child: ChatMessagesList(
          currentUsername: 'me',
          readCounts: const {'2': 3},
          messages: [
            msg('1', 'a-very-long-username-that-keeps-going-and-going', long, t),
            msg('2', 'me', long, t),
            msg('3', 'bob', '짧은 말', t),
          ],
        ),
      ),
      ChatInput(isConnected: true, onSend: (_) {}, onFilePick: (_, __, ___, ____) async {}),
    ])));
    await tester.pump();

    expect(tester.takeException(), isNull);
  });
}
