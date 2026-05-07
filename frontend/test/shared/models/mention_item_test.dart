import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/mention_item.dart';

void main() {
  group('MentionItem.fromJson', () {
    test('parses canonical backend shape', () {
      final json = {
        'messageId': 'm-42',
        'chatRoomId': 'room-1',
        'fromUsername': 'bob',
        'contentPreview': '@alice 회의 어떻게 되어가요?',
        'timestamp': '2026-05-07T14:30:00',
        'read': false,
      };
      final m = MentionItem.fromJson(json);
      expect(m.messageId, 'm-42');
      expect(m.chatRoomId, 'room-1');
      expect(m.fromUsername, 'bob');
      expect(m.contentPreview, '@alice 회의 어떻게 되어가요?');
      expect(m.timestamp, '2026-05-07T14:30:00');
      expect(m.read, false);
    });

    test('defaults missing fields to safe values', () {
      final m = MentionItem.fromJson({});
      expect(m.messageId, '');
      expect(m.chatRoomId, '');
      expect(m.fromUsername, '');
      expect(m.contentPreview, '');
      expect(m.timestamp, '');
      expect(m.read, false);
    });

    test('read coerces to bool from various truthy/falsy', () {
      expect(MentionItem.fromJson({'read': true}).read, true);
      expect(MentionItem.fromJson({'read': false}).read, false);
      expect(MentionItem.fromJson({'read': 1}).read, false); // strict equality
      expect(MentionItem.fromJson({'read': 'true'}).read, false);
      expect(MentionItem.fromJson({}).read, false);
    });

    test('when parses ISO-8601', () {
      final m = MentionItem.fromJson({
        'messageId': 'm1',
        'timestamp': '2026-05-07T14:30:00',
      });
      expect(m.when, DateTime.parse('2026-05-07T14:30:00'));
    });

    test('when falls back to now() on bad input', () {
      final before = DateTime.now();
      final m = MentionItem.fromJson({'timestamp': 'garbage'});
      expect(
        m.when.isAfter(before.subtract(const Duration(seconds: 1))),
        true,
      );
    });

    test('copyWith updates only read', () {
      final original = MentionItem.fromJson({
        'messageId': 'm1',
        'chatRoomId': 'r',
        'fromUsername': 'bob',
        'contentPreview': 'hi',
        'timestamp': '2026-05-07T14:30:00',
        'read': false,
      });
      final updated = original.copyWith(read: true);
      expect(updated.read, true);
      expect(updated.messageId, 'm1');
      expect(updated.contentPreview, 'hi');
      // original unchanged
      expect(original.read, false);
    });
  });
}
