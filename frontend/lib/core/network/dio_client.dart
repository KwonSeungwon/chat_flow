import 'dart:ui';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

final dioClientProvider = Provider<DioClient>((ref) => DioClient());

class DioClient {
  late final Dio _dio;
  static const _storage = FlutterSecureStorage();

  /// Called when a 401 response is received — auth provider hooks this to reset state.
  VoidCallback? onUnauthorized;

  DioClient() {
    final baseUrl = kIsWeb
        ? _webOrigin()
        : (dotenv.env['API_BASE_URL'] ?? 'http://43.201.22.86:8000');
    _dio = Dio(BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 30),
      headers: {'Content-Type': 'application/json'},
    ));

    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await _storage.read(key: 'chatflow-token');
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        return handler.next(options);
      },
      onError: (error, handler) async {
        if (error.response?.statusCode == 401) {
          await _storage.deleteAll();
          onUnauthorized?.call();
        }
        return handler.next(error);
      },
    ));
  }

  Dio get dio => _dio;

  static String _webOrigin() {
    final uri = Uri.base;
    final port = (uri.hasPort && uri.port != 80 && uri.port != 443)
        ? ':${uri.port}'
        : '';
    return '${uri.scheme}://${uri.host}$port';
  }
}
