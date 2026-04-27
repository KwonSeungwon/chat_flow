import 'dart:typed_data';
import 'dart:ui';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import '../constants/storage_keys.dart';

final dioClientProvider = Provider<DioClient>((ref) => DioClient());

class DioClient {
  late final Dio _dio;
  static const _storage = FlutterSecureStorage();

  /// Called when a 401 response is received — auth provider hooks this to reset state.
  VoidCallback? onUnauthorized;

  DioClient() {
    final baseUrl = kIsWeb
        ? _webOrigin()
        : (dotenv.env['API_BASE_URL'] ?? 'https://app.chatflow.ai.kr');
    _dio = Dio(BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 30),
      headers: {'Content-Type': 'application/json'},
    ));

    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await _storage.read(key: StorageKeys.token);
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        return handler.next(options);
      },
      onError: (error, handler) async {
        if (error.response?.statusCode == 401) {
          // /api/auth/* (login/register)의 401은 잘못된 자격증명 응답이므로 logout
          // 처리하면 안 된다. 그 외 401은 토큰 만료/무효 → logout 처리.
          final path = error.requestOptions.path;
          final isAuthEndpoint = path.startsWith('/api/auth/');
          if (!isAuthEndpoint) {
            // 저장소에 토큰이 있으면 만료된 것. 헤더 검사 대신 storage 직접 조회 (대소문자/Dio 직렬화 무관).
            try {
              final stored = await _storage.read(key: StorageKeys.token);
              if (stored != null) {
                await _storage.delete(key: StorageKeys.token);
                await _storage.delete(key: StorageKeys.userId);
                await _storage.delete(key: StorageKeys.username);
              }
            } catch (_) {/* best-effort */}
            // storage 동작과 무관하게 항상 onUnauthorized 호출 → state 리셋 + router redirect
            onUnauthorized?.call();
          }
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
