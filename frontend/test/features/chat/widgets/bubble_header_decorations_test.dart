import 'package:gossiplog/features/chat/widgets/bubbles/bubble_header_decorations.dart';
import 'package:gossiplog/shared/models/chat_message.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('long forwarded-from name ellipsizes instead of overflowing',
      (tester) async {
    final msg = ChatMessage(
      chatRoomId: 'r',
      userId: 'u1',
      username: 'alice',
      content: 'hi',
      timestamp: '2026-10-16T10:00:00',
      type: 'CHAT',
      forwardedFrom: '아주 긴 채팅방 이름' * 10,
    );
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Center(
          child: SizedBox(
            width: 160,
            child: BubbleHeaderDecorations(
                msg: msg, isMine: true, showAvatar: false),
          ),
        ),
      ),
    ));

    expect(tester.takeException(), isNull);
    final text = tester.widget<Text>(find.textContaining('전달됨'));
    expect(text.overflow, TextOverflow.ellipsis);
  });
}
