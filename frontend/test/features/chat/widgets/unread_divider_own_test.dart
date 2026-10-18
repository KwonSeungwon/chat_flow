import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/features/chat/widgets/chat_messages_list.dart';
import 'package:gossiplog/shared/models/chat_message.dart';

void main() {
  ChatMessage msg(String id, String user, [String type = 'CHAT']) => ChatMessage(
        messageId: id,
        chatRoomId: 'r1',
        userId: '$user-id',
        username: user,
        content: 'c-$id',
        timestamp: '2026-10-16T10:00:00Z',
        type: type,
      );

  Future<void> pump(WidgetTester tester, List<ChatMessage> messages) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ChatMessagesList(
          currentUsername: 'bob',
          lastReadMessageId: 'm1',
          messages: messages,
        ),
      ),
    ));
    await tester.pump();
  }

  testWidgets('lastRead 뒤에 내 메시지만 있으면 안 읽음 구분선이 없다', (tester) async {
    await pump(tester, [msg('m1', 'alice'), msg('m2', 'bob'), msg('m3', 'bob')]);
    expect(find.text('여기까지 읽었습니다'), findsNothing);
  });

  testWidgets('lastRead 뒤에 남의 메시지가 있으면 lastRead 바로 뒤에 구분선', (tester) async {
    await pump(tester, [msg('m1', 'alice'), msg('m2', 'bob'), msg('m3', 'alice')]);
    expect(find.text('여기까지 읽었습니다'), findsOneWidget);
    final divY = tester.getTopLeft(find.text('여기까지 읽었습니다')).dy;
    expect(tester.getTopLeft(find.text('c-m1')).dy, lessThan(divY));
    expect(tester.getTopLeft(find.text('c-m2')).dy, greaterThan(divY));
  });

  testWidgets('lastRead가 마지막 메시지면 구분선이 없다', (tester) async {
    await pump(tester, [msg('m0', 'alice'), msg('m1', 'alice')]);
    expect(find.text('여기까지 읽었습니다'), findsNothing);
  });

  testWidgets('lastRead 뒤에 내 메시지와 남의 JOIN/LEAVE만 있으면 구분선이 없다', (tester) async {
    await pump(tester, [
      msg('m1', 'alice'),
      msg('m2', 'bob'),
      msg('m3', 'carol', 'JOIN'),
      msg('m4', 'carol', 'LEAVE'),
    ]);
    expect(find.text('여기까지 읽었습니다'), findsNothing);
  });
}
