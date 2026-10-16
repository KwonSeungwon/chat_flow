import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/bubbles/chat_bubble.dart';
import 'package:chatflow/shared/models/chat_message.dart';

/// 버블 본문의 @멘션 하이라이트는 "서버가 멘션 행을 만든다"는 약속이다.
/// 전달(forward)된 글은 남이 쓴 문장을 재조립한 것이라 서버가 행을 만들지 않으므로
/// (chat-service {@code MentionTargets.shouldResolveMentions}), 여기서 강조하면
/// 오지 않을 알림을 약속하게 된다.
void main() {
  const content = '@bob 이거 확인해줘';

  ChatMessage message({String? forwardedFrom}) => ChatMessage(
        messageId: 'm1',
        chatRoomId: 'r1',
        userId: 'alice-id',
        username: 'alice',
        content: content,
        timestamp: '2026-07-28T10:00:00Z',
        type: 'CHAT',
        forwardedFrom: forwardedFrom,
      );

  Widget wrap(ChatMessage msg) => MaterialApp(
        home: Scaffold(
          body: ChatBubble(msg: msg, isMine: false, time: '10:00', me: 'bob'),
        ),
      );

  /// Text.rich — 스팬으로 쪼개져 하이라이트가 붙은 상태.
  final highlighted = find.byWidgetPredicate(
      (w) => w is Text && w.data == null && w.textSpan?.toPlainText() == content);

  /// 평문 Text — 하이라이트 없음.
  final plain = find.byWidgetPredicate((w) => w is Text && w.data == content);

  group('ChatBubble 멘션 하이라이트', () {
    testWidgets('일반 메시지의 @내이름은 강조된다', (tester) async {
      await tester.pumpWidget(wrap(message()));

      expect(highlighted, findsOneWidget);
      expect(plain, findsNothing);
    });

    testWidgets('전달된 메시지는 강조하지 않는다 — 서버가 멘션 행을 만들지 않는다',
        (tester) async {
      await tester.pumpWidget(wrap(message(forwardedFrom: 'carol: $content')));

      expect(plain, findsOneWidget);
      expect(highlighted, findsNothing);
    });

    testWidgets('공백뿐인 forwardedFrom은 전달이 아니다 — 서버 판정과 같아야 한다',
        (tester) async {
      // MentionTargets는 isBlank()로 걸러 멘션 행을 만든다. 여기서 전달로 보면
      // 알림은 오는데 버블에는 아무 표시가 없다.
      await tester.pumpWidget(wrap(message(forwardedFrom: '   ')));

      expect(highlighted, findsOneWidget);
      expect(plain, findsNothing);
    });
  });
}
