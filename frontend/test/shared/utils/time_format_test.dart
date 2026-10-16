import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/utils/time_format.dart';

void main() {
  // Fixed reference point for deterministic tests.
  final now = DateTime(2026, 7, 21, 14, 30, 0);

  group('relativeTime (full)', () {
    test('returns 방금 for < 1 minute', () {
      expect(relativeTime(now.subtract(const Duration(seconds: 0)), now: now), '방금');
      expect(relativeTime(now.subtract(const Duration(seconds: 30)), now: now), '방금');
      expect(relativeTime(now.subtract(const Duration(seconds: 59)), now: now), '방금');
    });

    test('returns N분 전 for 1..59 minutes', () {
      expect(relativeTime(now.subtract(const Duration(minutes: 1)), now: now), '1분 전');
      expect(relativeTime(now.subtract(const Duration(minutes: 30)), now: now), '30분 전');
      expect(relativeTime(now.subtract(const Duration(minutes: 59)), now: now), '59분 전');
    });

    test('returns N시간 전 for 1..23 hours', () {
      expect(relativeTime(now.subtract(const Duration(hours: 1)), now: now), '1시간 전');
      expect(relativeTime(now.subtract(const Duration(hours: 12)), now: now), '12시간 전');
      expect(relativeTime(now.subtract(const Duration(hours: 23)), now: now), '23시간 전');
    });

    test('returns N일 전 for 1..6 days', () {
      expect(relativeTime(now.subtract(const Duration(days: 1)), now: now), '1일 전');
      expect(relativeTime(now.subtract(const Duration(days: 6)), now: now), '6일 전');
    });

    test('returns yyyy-MM-dd for >= 7 days', () {
      expect(relativeTime(now.subtract(const Duration(days: 7)), now: now), '2026-07-14');
      expect(relativeTime(DateTime(2026, 1, 5, 10, 0), now: now), '2026-01-05');
    });

    test('boundary: exactly 1 minute ago', () {
      expect(relativeTime(now.subtract(const Duration(seconds: 60)), now: now), '1분 전');
    });

    test('boundary: exactly 1 hour ago', () {
      expect(relativeTime(now.subtract(const Duration(minutes: 60)), now: now), '1시간 전');
    });

    test('boundary: exactly 1 day ago', () {
      expect(relativeTime(now.subtract(const Duration(hours: 24)), now: now), '1일 전');
    });

    test('boundary: exactly 7 days ago', () {
      expect(relativeTime(now.subtract(const Duration(days: 7)), now: now), '2026-07-14');
    });
  });

  group('relativeTimeCompact', () {
    test('returns 방금 for < 1 minute', () {
      expect(relativeTimeCompact(now.subtract(const Duration(seconds: 30)), now: now), '방금');
    });

    test('returns N분 전 for 1..59 minutes', () {
      expect(relativeTimeCompact(now.subtract(const Duration(minutes: 5)), now: now), '5분 전');
      expect(relativeTimeCompact(now.subtract(const Duration(minutes: 59)), now: now), '59분 전');
    });

    test('returns N시간 전 for 1..23 hours', () {
      expect(relativeTimeCompact(now.subtract(const Duration(hours: 3)), now: now), '3시간 전');
      expect(relativeTimeCompact(now.subtract(const Duration(hours: 23)), now: now), '23시간 전');
    });

    test('returns M/d for >= 24 hours (no days-ago tier)', () {
      final dt = now.subtract(const Duration(days: 2));
      expect(relativeTimeCompact(dt, now: now), '${dt.month}/${dt.day}');
    });

    test('returns M/d for 7+ days', () {
      final dt = now.subtract(const Duration(days: 30));
      expect(relativeTimeCompact(dt, now: now), '${dt.month}/${dt.day}');
    });
  });

  group('hhmm', () {
    test('formats single-digit hour with leading zero', () {
      expect(hhmm(DateTime(2026, 1, 1, 9, 5)), '09:05');
    });

    test('formats double-digit hour', () {
      expect(hhmm(DateTime(2026, 1, 1, 14, 30)), '14:30');
    });

    test('midnight', () {
      expect(hhmm(DateTime(2026, 1, 1, 0, 0)), '00:00');
    });

    test('end of day', () {
      expect(hhmm(DateTime(2026, 1, 1, 23, 59)), '23:59');
    });
  });
}
