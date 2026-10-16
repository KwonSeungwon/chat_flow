import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/bubbles/chat_bubble.dart';
import 'package:chatflow/features/chat/widgets/chat_messages_list.dart';
import 'package:chatflow/shared/models/chat_message.dart';

/// 서버가 더 이상 만들지 않는 옛 메시지 타입이나 JSON 본문이 와도
/// 앱은 일반 말풍선으로 그리고 죽지 않아야 한다.
void main() {
  ChatMessage message(String id, String type, String content) => ChatMessage(
        messageId: id,
        chatRoomId: 'r1',
        userId: 'alice-id',
        username: 'alice',
        content: content,
        timestamp: '2026-10-16T10:00:00Z',
        type: type,
      );

  testWidgets('알 수 없는 타입과 JSON 본문은 일반 말풍선으로 그린다', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ChatMessagesList(
          currentUsername: 'bob',
          messages: [
            message('m1', 'LEGACY_CARD', '{"name":"x"}'),
            message('m2', 'CHAT', '{"type":"LEGACY","a":"b"}'),
          ],
        ),
      ),
    ));
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.byType(ChatBubble), findsNWidgets(2));
    expect(find.text('{"name":"x"}'), findsOneWidget);
    expect(find.text('{"type":"LEGACY","a":"b"}'), findsOneWidget);
  });
}
