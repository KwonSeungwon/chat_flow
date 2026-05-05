import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/admin/room_admin_api.dart'
    show parseMutedUntil;

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
}
