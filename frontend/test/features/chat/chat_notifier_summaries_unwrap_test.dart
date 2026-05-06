import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/chat_notifier.dart'
    show parseSummariesResponse;

void main() {
  group('parseSummariesResponse', () {
    Map<String, dynamic> sampleSummary(String id) => {
          'messageId': id,
          'chatRoomId': 'room-1',
          'userId': 'ai',
          'username': 'AI',
          'content': 'summary',
          'timestamp': '2026-05-05T12:00:00',
          'type': 'AI_SUMMARY',
        };

    test('parses ApiResponse-wrapped list', () {
      final data = {
        'success': true,
        'data': [sampleSummary('s-1')],
        'message': null,
      };
      final result = parseSummariesResponse(data);
      expect(result, hasLength(1));
      expect(result.first.messageId, 's-1');
    });

    test('parses bare list (legacy/cached shape)', () {
      final data = [sampleSummary('s-2')];
      final result = parseSummariesResponse(data);
      expect(result, hasLength(1));
      expect(result.first.messageId, 's-2');
    });

    test('returns empty for ApiResponse error envelope', () {
      // After the fix, backend errors come through Spring's global handler as
      // {success:false, message:"...", data:null}. The old code silently
      // fell through to "no summaries"; the new helper still returns [],
      // but importantly does not crash and is testable.
      final data = {'success': false, 'message': 'AI down', 'data': null};
      expect(parseSummariesResponse(data), isEmpty);
    });

    test('returns empty when payload is null', () {
      expect(parseSummariesResponse(null), isEmpty);
    });

    test('returns empty when payload is a string', () {
      expect(parseSummariesResponse('garbage'), isEmpty);
    });

    test('returns empty when data field is not a list', () {
      final data = {'success': true, 'data': {'wrong': 'shape'}};
      expect(parseSummariesResponse(data), isEmpty);
    });

    test('parses multiple summaries in correct order', () {
      final data = {
        'success': true,
        'data': [
          sampleSummary('s-1'),
          sampleSummary('s-2'),
          sampleSummary('s-3'),
        ],
      };
      final result = parseSummariesResponse(data);
      expect(result.map((m) => m.messageId).toList(),
          ['s-1', 's-2', 's-3']);
    });
  });
}
