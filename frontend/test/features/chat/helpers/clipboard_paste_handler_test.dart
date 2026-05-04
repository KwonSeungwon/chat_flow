import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/helpers/clipboard_paste_handler.dart';

void main() {
  group('readClipboardImage', () {
    test('returns null on native (test) environment', () async {
      // In the test (non-web) environment, the stub is used which always
      // returns null since native platforms do not support clipboard image
      // reading through the browser API.
      final result = await readClipboardImage();
      expect(result, isNull);
    });
  });
}
