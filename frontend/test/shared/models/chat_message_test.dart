import 'package:chatflow/shared/models/chat_message.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ChatMessage.toJson()', () {
    test('round-trip -- fromJson(toJson()) == original', () {
      final original = ChatMessage(
        id: 'id-1',
        messageId: 'msg-1',
        chatRoomId: 'room-1',
        userId: 'user-1',
        username: 'tester',
        content: 'hello',
        timestamp: '2026-01-01T00:00:00.000Z',
        type: 'CHAT',
        deleted: true,
        edited: true,
        editedAt: '2026-01-01T01:00:00.000Z',
        pinned: true,
        reactions: {
          '\u{1F44D}': ['user-1', 'user-2'],
        },
        localId: 'local-1',
      );

      final json = original.toJson();
      final restored = ChatMessage.fromJson(json);

      expect(restored.id, 'id-1');
      expect(restored.messageId, 'msg-1');
      expect(restored.deleted, true);
      expect(restored.edited, true);
      expect(restored.editedAt, '2026-01-01T01:00:00.000Z');
      expect(restored.pinned, true);
      expect(restored.reactions['\u{1F44D}'], ['user-1', 'user-2']);
      expect(restored.localId, 'local-1');
      expect(restored.chatRoomId, 'room-1');
      expect(restored.userId, 'user-1');
      expect(restored.username, 'tester');
      expect(restored.content, 'hello');
      expect(restored.type, 'CHAT');
    });

    test('fromJson이 localId를 올바르게 파싱', () {
      final json = {
        'chatRoomId': 'r', 'userId': 'u', 'username': 'n',
        'content': 'c', 'timestamp': 't', 'type': 'CHAT', 'localId': 'local-99',
      };
      final msg = ChatMessage.fromJson(json);
      expect(msg.localId, 'local-99');
    });

    test('null optional fields are excluded from toJson', () {
      final msg = ChatMessage(
        chatRoomId: 'room-1',
        userId: 'user-1',
        username: 'tester',
        content: 'hello',
        timestamp: '2026-01-01T00:00:00.000Z',
        type: 'CHAT',
      );
      final json = msg.toJson();
      expect(json.containsKey('id'), false);
      expect(json.containsKey('messageId'), false);
      expect(json.containsKey('editedAt'), false);
      expect(json.containsKey('localId'), false);
    });

    test('empty reactions are excluded from toJson', () {
      final msg = ChatMessage(
        chatRoomId: 'r',
        userId: 'u',
        username: 'n',
        content: 'c',
        timestamp: 't',
        type: 'CHAT',
        reactions: const {},
      );
      final json = msg.toJson();
      expect(json.containsKey('reactions'), false);
    });
  });
}
