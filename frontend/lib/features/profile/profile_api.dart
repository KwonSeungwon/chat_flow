import 'package:dio/dio.dart';

import '../../core/network/api_response.dart';
import '../../shared/models/user_profile.dart';

class ProfileApi {
  final Dio _dio;
  ProfileApi(this._dio);

  Future<UserProfile> getMe() async {
    final res = await _dio.get('/api/users/me');
    return UserProfile.fromJson(_unwrapProfile(res.data));
  }

  Future<UserProfile> getById(String userId) async {
    final res = await _dio.get('/api/users/$userId');
    return UserProfile.fromJson(_unwrapProfile(res.data));
  }

  /// Partial update.
  ///   - 변경 안 할 필드: 인자 생략 (null) → 백엔드에서 그대로 유지
  ///   - 명시적 비우기: 빈 문자열 ""
  Future<UserProfile> updateMe({
    String? profileImageUrl,
    String? statusMessage,
    String? bio,
  }) async {
    final body = <String, dynamic>{};
    if (profileImageUrl != null) body['profileImageUrl'] = profileImageUrl;
    if (statusMessage != null) body['statusMessage'] = statusMessage;
    if (bio != null) body['bio'] = bio;

    final res = await _dio.patch('/api/users/me', data: body);
    return UserProfile.fromJson(_unwrapProfile(res.data));
  }

  Map<String, dynamic> _unwrapProfile(Object? body) {
    final data = apiResponseMap(body);
    if (data != null) return data;
    throw ArgumentError('Unexpected profile response shape: $body');
  }
}
