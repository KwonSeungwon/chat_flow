import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/chat_messages_list.dart';
import 'package:chatflow/shared/models/chat_message.dart';

ChatMessage _msg(String id, String ts) => ChatMessage(
      messageId: id,
      chatRoomId: 'room1',
      userId: 'u1',
      username: 'alice',
      content: 'hi',
      timestamp: ts,
      type: 'CHAT',
    );

void main() {
  group('isPrependedHistory', () {
    test('returns false when old list is empty (initial load)', () {
      final newMsgs = [_msg('m1', '2026-01-01T00:00:00Z')];
      expect(isPrependedHistory([], newMsgs), isFalse);
    });

    test('returns false when new list is empty', () {
      final oldMsgs = [_msg('m1', '2026-01-01T00:00:00Z')];
      expect(isPrependedHistory(oldMsgs, []), isFalse);
    });

    test('returns false when both lists are empty', () {
      expect(isPrependedHistory([], []), isFalse);
    });

    test('returns true when head changed (older history prepended)', () {
      final oldMsgs = [
        _msg('m5', '2026-01-01T00:05:00Z'),
        _msg('m6', '2026-01-01T00:06:00Z'),
      ];
      final newMsgs = [
        _msg('m1', '2026-01-01T00:01:00Z'), // prepended
        _msg('m2', '2026-01-01T00:02:00Z'), // prepended
        _msg('m5', '2026-01-01T00:05:00Z'),
        _msg('m6', '2026-01-01T00:06:00Z'),
      ];
      expect(isPrependedHistory(oldMsgs, newMsgs), isTrue);
    });

    test('returns false when tail grew (new message appended)', () {
      final oldMsgs = [
        _msg('m1', '2026-01-01T00:01:00Z'),
        _msg('m2', '2026-01-01T00:02:00Z'),
      ];
      final newMsgs = [
        _msg('m1', '2026-01-01T00:01:00Z'),
        _msg('m2', '2026-01-01T00:02:00Z'),
        _msg('m3', '2026-01-01T00:03:00Z'), // appended
      ];
      expect(isPrependedHistory(oldMsgs, newMsgs), isFalse);
    });

    test('returns false when same head (messages unchanged)', () {
      final msgs = [
        _msg('m1', '2026-01-01T00:01:00Z'),
        _msg('m2', '2026-01-01T00:02:00Z'),
      ];
      expect(isPrependedHistory(msgs, msgs), isFalse);
    });
  });
}
