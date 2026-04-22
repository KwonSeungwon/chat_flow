import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_dotenv/flutter_dotenv.dart';

/// AuthNotifier가 state 변화 시 여기에 현재 토큰을 기록.
/// buildFullUrl에서 /api/files/* 경로에 동기적으로 query param 추가하려고 캐싱.
String? _cachedAuthTokenForFiles;

void setAuthTokenForFiles(String? token) {
  _cachedAuthTokenForFiles = (token != null && token.isNotEmpty) ? token : null;
}

String buildFullUrl(String relativeUrl) {
  if (relativeUrl.startsWith('http://') || relativeUrl.startsWith('https://')) {
    return relativeUrl;
  }
  String base;
  if (kIsWeb) {
    final uri = Uri.base;
    final port = (uri.hasPort && uri.port != 80 && uri.port != 443) ? ':${uri.port}' : '';
    base = '${uri.scheme}://${uri.host}$port$relativeUrl';
  } else {
    final apiBase = dotenv.env['API_BASE_URL'] ?? 'https://app.chatflow.ai.kr';
    base = '$apiBase$relativeUrl';
  }
  // /api/files/** 경로는 인증이 필요하지만 브라우저 <img>/<a>가 Bearer 헤더를
  // 붙이지 않으므로 Gateway가 지원하는 ?token= query param으로 인증.
  if (relativeUrl.startsWith('/api/files/') && _cachedAuthTokenForFiles != null) {
    final uri = Uri.parse(base);
    final params = Map<String, String>.from(uri.queryParameters);
    if (!params.containsKey('token')) {
      params['token'] = _cachedAuthTokenForFiles!;
    }
    return uri.replace(queryParameters: params).toString();
  }
  return base;
}
