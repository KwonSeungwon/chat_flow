import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/utils/server_time.dart';

void main() {
  group('parseServerDateTime', () {
    test('offset 없는 문자열은 UTC로 간주한다', () {
      final d = parseServerDateTime('2026-05-07T14:30:00');
      expect(d!.toUtc(), DateTime.utc(2026, 5, 7, 14, 30));
      expect(d.isUtc, isFalse, reason: 'toLocal 결과');
    });

    test('소수초가 있는 naive 문자열도 UTC', () {
      final d = parseServerDateTime('2026-05-07T14:30:00.123456');
      expect(d!.toUtc(), DateTime.utc(2026, 5, 7, 14, 30, 0, 123, 456));
    });

    test('Z 접미사는 그대로 UTC', () {
      expect(parseServerDateTime('2026-05-07T14:30:00Z')!.toUtc(),
          DateTime.utc(2026, 5, 7, 14, 30));
    });

    test('+09:00 offset을 존중한다', () {
      expect(parseServerDateTime('2026-05-07T23:30:00+09:00')!.toUtc(),
          DateTime.utc(2026, 5, 7, 14, 30));
    });

    test('-05:00 offset을 존중한다', () {
      expect(parseServerDateTime('2026-05-07T09:30:00-05:00')!.toUtc(),
          DateTime.utc(2026, 5, 7, 14, 30));
    });

    test('Jackson 배열 형식은 UTC', () {
      expect(
          parseServerDateTime('[2026, 5, 7, 14, 30, 0, 123000000]')!.toUtc(),
          DateTime.utc(2026, 5, 7, 14, 30, 0, 123));
    });

    test('null / 빈 문자열 / 쓰레기는 null', () {
      expect(parseServerDateTime(null), isNull);
      expect(parseServerDateTime(''), isNull);
      expect(parseServerDateTime('garbage'), isNull);
      expect(parseServerDateTime('[1, 2]'), isNull);
    });
  });
}
