import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_dotenv/flutter_dotenv.dart';

/// Resolves the STOMP WebSocket URL. On web, derives it from the current
/// origin ([Uri.base]); otherwise uses `WS_URL` from `.env` with a prod
/// fallback.
String resolveStompWsUrl() {
  if (kIsWeb) return deriveWebStompUrl(Uri.base);
  return dotenv.env['WS_URL'] ?? 'wss://app.chatflow.ai.kr/ws-native';
}

/// Pure: derive the web WS URL from a base URI.
///
/// Scheme mapping: `https` -> `wss`, everything else -> `ws`.
/// Default ports (80 / 443) are omitted; non-default ports are kept.
String deriveWebStompUrl(Uri base) {
  final scheme = base.scheme == 'https' ? 'wss' : 'ws';
  final port = (base.hasPort && base.port != 80 && base.port != 443)
      ? ':${base.port}'
      : '';
  return '$scheme://${base.host}$port/ws-native';
}
