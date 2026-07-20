import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/stomp_support.dart';

void main() {
  group('deriveWebStompUrl', () {
    test('https maps to wss', () {
      final uri = Uri.parse('https://example.com/');
      expect(deriveWebStompUrl(uri), 'wss://example.com/ws-native');
    });

    test('http maps to ws', () {
      final uri = Uri.parse('http://example.com/');
      expect(deriveWebStompUrl(uri), 'ws://example.com/ws-native');
    });

    test('default port 443 is omitted for https', () {
      final uri = Uri.parse('https://example.com:443/');
      expect(deriveWebStompUrl(uri), 'wss://example.com/ws-native');
    });

    test('default port 80 is omitted for http', () {
      final uri = Uri.parse('http://example.com:80/');
      expect(deriveWebStompUrl(uri), 'ws://example.com/ws-native');
    });

    test('custom port is included', () {
      final uri = Uri.parse('http://localhost:8080/');
      expect(deriveWebStompUrl(uri), 'ws://localhost:8080/ws-native');
    });

    test('custom port is included for https', () {
      final uri = Uri.parse('https://localhost:3000/');
      expect(deriveWebStompUrl(uri), 'wss://localhost:3000/ws-native');
    });

    test('host is preserved', () {
      final uri = Uri.parse('https://app.chatflow.ai.kr/');
      expect(deriveWebStompUrl(uri), 'wss://app.chatflow.ai.kr/ws-native');
    });

    test('path in base URI is ignored', () {
      final uri = Uri.parse('https://example.com/some/path');
      expect(deriveWebStompUrl(uri), 'wss://example.com/ws-native');
    });
  });
}
