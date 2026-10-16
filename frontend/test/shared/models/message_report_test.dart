import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/message_report.dart';

void main() {
  group('ReportReason', () {
    test('apiValue returns uppercase', () {
      expect(ReportReason.spam.apiValue, 'SPAM');
      expect(ReportReason.harassment.apiValue, 'HARASSMENT');
      expect(ReportReason.inappropriate.apiValue, 'INAPPROPRIATE');
      expect(ReportReason.other.apiValue, 'OTHER');
    });

    test('fromString parses case-insensitively', () {
      expect(ReportReasonX.fromString('SPAM'), ReportReason.spam);
      expect(ReportReasonX.fromString('harassment'), ReportReason.harassment);
      expect(ReportReasonX.fromString('Inappropriate'), ReportReason.inappropriate);
    });

    test('fromString falls back to other for unknown', () {
      expect(ReportReasonX.fromString('INVALID'), ReportReason.other);
      expect(ReportReasonX.fromString(''), ReportReason.other);
    });
  });

  group('ReportStatus', () {
    test('apiValue returns uppercase', () {
      expect(ReportStatus.pending.apiValue, 'PENDING');
      expect(ReportStatus.resolved.apiValue, 'RESOLVED');
      expect(ReportStatus.dismissed.apiValue, 'DISMISSED');
    });

    test('fromString parses case-insensitively', () {
      expect(ReportStatusX.fromString('PENDING'), ReportStatus.pending);
      expect(ReportStatusX.fromString('resolved'), ReportStatus.resolved);
      expect(ReportStatusX.fromString('Dismissed'), ReportStatus.dismissed);
    });

    test('fromString falls back to pending for unknown', () {
      expect(ReportStatusX.fromString('UNKNOWN'), ReportStatus.pending);
    });
  });

  group('MessageReport.fromJson', () {
    test('parses all fields correctly', () {
      final report = MessageReport.fromJson({
        'id': 42,
        'messageId': 'msg-1',
        'messageContent': 'Bad content here',
        'messageAuthor': 'spammer',
        'reportedBy': 'reporter',
        'reportedByUserId': 'uid-reporter',
        'reason': 'SPAM',
        'comment': 'This is spam',
        'status': 'PENDING',
        'createdAt': '2026-04-27T12:00:00.000',
      });
      expect(report.id, 42);
      expect(report.messageId, 'msg-1');
      expect(report.messageContent, 'Bad content here');
      expect(report.messageAuthor, 'spammer');
      expect(report.reportedBy, 'reporter');
      expect(report.reportedByUserId, 'uid-reporter');
      expect(report.reason, ReportReason.spam);
      expect(report.comment, 'This is spam');
      expect(report.status, ReportStatus.pending);
      expect(report.createdAt.year, 2026);
    });

    test('handles missing optional fields', () {
      final report = MessageReport.fromJson({
        'id': 1,
        'messageId': 'msg-2',
        'messageContent': 'Content',
        'messageAuthor': 'author',
        'reportedBy': 'reporter',
        'reason': 'OTHER',
        'status': 'RESOLVED',
        'createdAt': '2026-01-01T00:00:00.000',
      });
      expect(report.reportedByUserId, isNull);
      expect(report.comment, isNull);
      expect(report.status, ReportStatus.resolved);
    });

    test('handles numeric id as double', () {
      final report = MessageReport.fromJson({
        'id': 99.0,
        'messageId': 'msg-3',
        'messageContent': '',
        'messageAuthor': '',
        'reportedBy': '',
        'reason': 'HARASSMENT',
        'status': 'DISMISSED',
        'createdAt': '2026-01-01T00:00:00.000',
      });
      expect(report.id, 99);
      expect(report.reason, ReportReason.harassment);
      expect(report.status, ReportStatus.dismissed);
    });
  });

  group('MessageReport.toJson', () {
    test('round-trips correctly', () {
      final original = MessageReport(
        id: 10,
        messageId: 'msg-1',
        messageContent: 'content',
        messageAuthor: 'author',
        reportedBy: 'reporter',
        reportedByUserId: 'uid',
        reason: ReportReason.inappropriate,
        comment: 'test comment',
        status: ReportStatus.pending,
        createdAt: DateTime.utc(2026, 4, 27),
      );
      final json = original.toJson();
      final restored = MessageReport.fromJson(json);
      expect(restored.id, original.id);
      expect(restored.messageId, original.messageId);
      expect(restored.reason, original.reason);
      expect(restored.comment, original.comment);
      expect(restored.status, original.status);
    });

    test('omits null optional fields', () {
      final report = MessageReport(
        id: 1,
        messageId: 'msg',
        messageContent: 'c',
        messageAuthor: 'a',
        reportedBy: 'r',
        reason: ReportReason.spam,
        status: ReportStatus.pending,
        createdAt: DateTime.utc(2026),
      );
      final json = report.toJson();
      expect(json.containsKey('reportedByUserId'), isFalse);
      expect(json.containsKey('comment'), isFalse);
    });
  });
}
