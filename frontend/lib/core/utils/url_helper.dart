import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_dotenv/flutter_dotenv.dart';

String buildFullUrl(String relativeUrl) {
  if (relativeUrl.startsWith('http://') || relativeUrl.startsWith('https://')) {
    return relativeUrl;
  }
  if (kIsWeb) {
    final uri = Uri.base;
    final port = (uri.hasPort && uri.port != 80 && uri.port != 443) ? ':${uri.port}' : '';
    return '${uri.scheme}://${uri.host}$port$relativeUrl';
  }
  final base = dotenv.env['API_BASE_URL'] ?? 'https://app.chatflow.ai.kr';
  return '$base$relativeUrl';
}
