import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/admin/room_admin_api.dart'
    show parseMutedUntil, parseReportId;

void main() {
  group('parseMutedUntil', () {
    test('unwraps ApiResponse and returns the actual mutedUntil', () {
      const mutedUntil = '2026-05-05T12:00:00';
      final data = {
        'success': true,
        'data': {'mutedUntil': mutedUntil},
        'message': null,
      };
      expect(parseMutedUntil(data), DateTime.parse(mutedUntil));
    });

    test('falls back to legacy root-level shape', () {
      const mutedUntil = '2026-05-05T12:00:00';
      final data = {'mutedUntil': mutedUntil};
      expect(parseMutedUntil(data), DateTime.parse(mutedUntil));
    });

    test('returns now() when ApiResponse data is missing', () {
      final before = DateTime.now();
      final result = parseMutedUntil({'success': false, 'data': null});
      expect(
        result.isAfter(before.subtract(const Duration(seconds: 1))),
        true,
      );
    });

    test('returns now() when payload is not a map', () {
      final before = DateTime.now();
      final result = parseMutedUntil('garbage');
      expect(
        result.isAfter(before.subtract(const Duration(seconds: 1))),
        true,
      );
    });
  });

  group('parseReportId', () {
    test('unwraps ApiResponse and returns the actual reportId', () {
      final data = {
        'success': true,
        'data': {'reportId': 42},
        'message': null,
      };
      expect(parseReportId(data), 42);
    });

    test('falls back to legacy root-level shape', () {
      final data = {'reportId': 99};
      expect(parseReportId(data), 99);
    });

    test('returns 0 when ApiResponse data is missing', () {
      expect(parseReportId({'success': false, 'data': null}), 0);
    });

    test('returns 0 when payload is not a map', () {
      expect(parseReportId('garbage'), 0);
    });

    test('returns 0 when reportId is not numeric', () {
      expect(parseReportId({'data': {'reportId': 'not-a-number'}}), 0);
    });

    test('coerces num to int (handles JSON double like 42.0)', () {
      expect(parseReportId({'data': {'reportId': 42.0}}), 42);
    });
  });
}
