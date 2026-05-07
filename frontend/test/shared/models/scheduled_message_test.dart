import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/scheduled_message.dart';

void main() {
  group('ScheduledMessage.fromJson', () {
    test('parses canonical backend shape', () {
      final json = {
        'id': 42,
        'chatRoomId': 'room-1',
        'content': 'hello future',
        'scheduledAt': '2026-05-08T09:00:00',
        'status': 'PENDING',
        'createdAt': '2026-05-07T14:30:00',
      };
      final m = ScheduledMessage.fromJson(json);
      expect(m.id, 42);
      expect(m.chatRoomId, 'room-1');
      expect(m.content, 'hello future');
      expect(m.scheduledAt, '2026-05-08T09:00:00');
      expect(m.status, 'PENDING');
      expect(m.createdAt, '2026-05-07T14:30:00');
      expect(m.isPending, true);
    });

    test('handles JSON int as num via .toInt()', () {
      final asInt = ScheduledMessage.fromJson({
        'id': 7,
        'chatRoomId': 'r',
        'content': 'x',
        'scheduledAt': '',
        'status': 'PENDING',
        'createdAt': '',
      });
      expect(asInt.id, 7);

      final asDouble = ScheduledMessage.fromJson({
        'id': 7.0,
        'chatRoomId': 'r',
        'content': 'x',
        'scheduledAt': '',
        'status': 'PENDING',
        'createdAt': '',
      });
      expect(asDouble.id, 7);
    });

    test('defaults missing fields to safe values', () {
      final m = ScheduledMessage.fromJson({'id': 1});
      expect(m.chatRoomId, '');
      expect(m.content, '');
      expect(m.scheduledAt, '');
      expect(m.status, 'PENDING');
      expect(m.createdAt, '');
    });

    test('isPending reflects status', () {
      final pending = ScheduledMessage.fromJson({'id': 1, 'status': 'PENDING'});
      final sent = ScheduledMessage.fromJson({'id': 2, 'status': 'SENT'});
      expect(pending.isPending, true);
      expect(sent.isPending, false);
    });

    test('scheduledAtDateTime parses ISO-8601', () {
      final m = ScheduledMessage.fromJson({
        'id': 1,
        'scheduledAt': '2026-05-08T09:00:00',
      });
      expect(m.scheduledAtDateTime, DateTime.parse('2026-05-08T09:00:00'));
    });

    test('scheduledAtDateTime falls back to now() on bad input', () {
      final before = DateTime.now();
      final m = ScheduledMessage.fromJson({
        'id': 1,
        'scheduledAt': 'garbage',
      });
      expect(
        m.scheduledAtDateTime.isAfter(before.subtract(const Duration(seconds: 1))),
        true,
      );
    });
  });
}
