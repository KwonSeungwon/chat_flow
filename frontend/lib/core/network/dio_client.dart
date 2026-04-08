import 'dart:typed_data';
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
        : (dotenv.env['API_BASE_URL'] ?? 'http://43.201.94.100:8000');
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
          await _storage.delete(key: 'chatflow-token');
          await _storage.delete(key: 'chatflow-userId');
          await _storage.delete(key: 'chatflow-username');
          onUnauthorized?.call();
        }
        return handler.next(error);
      },
    ));
  }

  Dio get dio => _dio;

  Future<Map<String, dynamic>> uploadFile({
    required String fileName,
    required Uint8List bytes,
    required String mimeType,
    void Function(int sent, int total)? onProgress,
  }) async {
    try {
      final formData = FormData.fromMap({
        'file': MultipartFile.fromBytes(
          bytes,
          filename: fileName,
          contentType: DioMediaType.parse(mimeType),
        ),
      });
      final resp = await _dio.post(
        '/api/files/upload',
        data: formData,
        onSendProgress: onProgress,
      );
      final data = resp.data;
      if (data is Map && data['data'] is Map) return data['data'] as Map<String, dynamic>;
      return data as Map<String, dynamic>;
    } on DioException catch (e) {
      if (e.response?.statusCode == 400) {
        throw Exception('지원하지 않는 파일 형식입니다.');
      } else if (e.response?.statusCode == 401) {
        throw Exception('로그인이 필요합니다.');
      } else if (e.response?.statusCode == 413) {
        throw Exception('파일 크기가 너무 큽니다 (최대 50MB).');
      }
      throw Exception('파일 업로드에 실패했습니다. 잠시 후 다시 시도해주세요.');
    }
  }

  static String _webOrigin() {
    final uri = Uri.base;
    final port = (uri.hasPort && uri.port != 80 && uri.port != 443)
        ? ':${uri.port}'
        : '';
    return '${uri.scheme}://${uri.host}$port';
  }
}
