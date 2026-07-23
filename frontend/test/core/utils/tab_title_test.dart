import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/utils/tab_title.dart';

void main() {
  group('formatTabTitle', () {
    test('returns plain title when unread is 0', () {
      expect(formatTabTitle(0), 'ChatFlow');
    });

    test('returns badged title for small count', () {
      expect(formatTabTitle(1), '(1) ChatFlow');
    });

    test('returns badged title at boundary 99', () {
      expect(formatTabTitle(99), '(99) ChatFlow');
    });

    test('caps at 99+ for count 100', () {
      expect(formatTabTitle(100), '(99+) ChatFlow');
    });

    test('caps at 99+ for large count', () {
      expect(formatTabTitle(999), '(99+) ChatFlow');
    });

    test('returns plain title for negative count', () {
      expect(formatTabTitle(-1), 'ChatFlow');
    });

    test('returns plain title for large negative count', () {
      expect(formatTabTitle(-100), 'ChatFlow');
    });
  });
}
