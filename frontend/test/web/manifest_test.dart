import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late Map<String, dynamic> manifest;

  setUpAll(() {
    // Resolve manifest.json relative to the project root (frontend/web/).
    // When tests run via `flutter test` the cwd is the frontend/ directory.
    final file = File('web/manifest.json');
    expect(file.existsSync(), isTrue, reason: 'web/manifest.json must exist');
    manifest = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
  });

  group('manifest.json PWA fields', () {
    test('has required top-level fields', () {
      for (final key in [
        'name',
        'short_name',
        'display',
        'theme_color',
        'background_color',
        'icons',
        'start_url',
        'scope',
      ]) {
        expect(manifest.containsKey(key), isTrue,
            reason: 'Missing required field: $key');
      }
    });

    test('display is standalone', () {
      expect(manifest['display'], equals('standalone'));
    });

    test('start_url is "."', () {
      expect(manifest['start_url'], equals('.'));
    });

    test('scope is "/"', () {
      expect(manifest['scope'], equals('/'));
    });

    test('theme_color matches app dark background', () {
      expect(manifest['theme_color'], equals('#0D0F14'));
    });

    test('background_color matches app dark background', () {
      expect(manifest['background_color'], equals('#0D0F14'));
    });

    test('icons include 192x192 and 512x512', () {
      final icons = manifest['icons'] as List<dynamic>;
      final sizes = icons
          .map((icon) => (icon as Map<String, dynamic>)['sizes'] as String)
          .toSet();
      expect(sizes.contains('192x192'), isTrue,
          reason: 'Must include a 192x192 icon');
      expect(sizes.contains('512x512'), isTrue,
          reason: 'Must include a 512x512 icon');
    });

    test('icons have valid src and type', () {
      final icons = manifest['icons'] as List<dynamic>;
      for (final icon in icons) {
        final map = icon as Map<String, dynamic>;
        expect(map['src'], isNotNull, reason: 'Icon src must not be null');
        expect(map['type'], equals('image/png'),
            reason: 'Icon type should be image/png');
      }
    });
  });
}
