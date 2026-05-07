import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/quick_reply.dart';

void main() {
  group('QuickReplySuggestions.fromJson', () {
    test('parses canonical backend shape', () {
      final json = {
        'suggestions': ['hi', 'sure', 'on it']
      };
      final s = QuickReplySuggestions.fromJson(json, 'm-1');
      expect(s.suggestions, ['hi', 'sure', 'on it']);
      expect(s.latestMessageId, 'm-1');
      expect(s.isEmpty, false);
    });

    test('filters empty and null entries', () {
      final json = {
        'suggestions': ['hi', '', 'ok', null]
      };
      final s = QuickReplySuggestions.fromJson(json, 'm-2');
      expect(s.suggestions, ['hi', 'ok']);
    });

    test('returns empty on missing or wrong-shape suggestions', () {
      expect(QuickReplySuggestions.fromJson({}, 'm').suggestions, isEmpty);
      expect(
          QuickReplySuggestions.fromJson(
                  {'suggestions': 'not-a-list'}, 'm')
              .suggestions,
          isEmpty);
    });

    test('isEmpty reflects suggestions list', () {
      expect(QuickReplySuggestions.empty.isEmpty, true);
      final populated = QuickReplySuggestions.fromJson(
          {'suggestions': ['x']}, 'm');
      expect(populated.isEmpty, false);
    });
  });
}
