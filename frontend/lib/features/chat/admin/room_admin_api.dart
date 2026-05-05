import 'package:dio/dio.dart';
import '../../../shared/models/message_report.dart';
import '../../../shared/models/room_ban.dart';
import '../../../shared/models/room_member.dart';
import '../../../shared/models/room_role.dart';

/// Extract `mutedUntil` from a possibly ApiResponse-wrapped payload.
/// Backend returns `{success, data: {mutedUntil}, ...}`. Older code paths
/// may have returned `{mutedUntil}` at the root, so we accept both.
DateTime parseMutedUntil(dynamic data) {
  String? raw;
  if (data is Map) {
    final inner = data['data'];
    if (inner is Map) {
      raw = inner['mutedUntil']?.toString();
    } else {
      raw = data['mutedUntil']?.toString();
    }
  }
  return DateTime.tryParse(raw ?? '') ?? DateTime.now();
}

/// Extract `reportId` from a possibly ApiResponse-wrapped payload.
/// Backend returns `{success, data: {reportId}, ...}`. Older code paths
/// may have returned `{reportId}` at the root, so we accept both.
/// Returns 0 when the payload is malformed or the id is missing.
int parseReportId(dynamic data) {
  num? rawId;
  if (data is Map) {
    final inner = data['data'];
    if (inner is Map) {
      final v = inner['reportId'];
      rawId = v is num ? v : null;
    } else {
      final v = data['reportId'];
      rawId = v is num ? v : null;
    }
  }
  return rawId?.toInt() ?? 0;
}

class RoomAdminApiException implements Exception {
  final String code;
  final String message;

  RoomAdminApiException({required this.code, required this.message});

  @override
  String toString() => 'RoomAdminApiException($code): $message';
}

class RoomAdminApi {
  final Dio _dio;

  RoomAdminApi(this._dio);

  // ---------------------------------------------------------------------------
  // Members (spec section 6.1)
  // ---------------------------------------------------------------------------

  Future<List<RoomMember>> listMembers(String roomId) async {
    try {
      final resp = await _dio.get('/api/chat/rooms/$roomId/members');
      final data = resp.data;
      List<dynamic> list;
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'] as List;
      } else {
        list = [];
      }
      return list
          .map((e) => RoomMember.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> changeRole(
      String roomId, String userId, RoomRole role) async {
    try {
      await _dio.patch(
        '/api/chat/rooms/$roomId/members/$userId/role',
        data: {'role': role.apiValue},
      );
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> kickMember(String roomId, String userId) async {
    try {
      await _dio.delete('/api/chat/rooms/$roomId/members/$userId');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<DateTime> muteMember(
      String roomId, String userId, int minutes) async {
    try {
      final resp = await _dio.post(
        '/api/chat/rooms/$roomId/members/$userId/mute',
        data: {'minutes': minutes},
      );
      return parseMutedUntil(resp.data);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> unmuteMember(String roomId, String userId) async {
    try {
      await _dio.delete('/api/chat/rooms/$roomId/members/$userId/mute');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Bans (spec section 6.2)
  // ---------------------------------------------------------------------------

  Future<List<RoomBan>> listBans(String roomId) async {
    try {
      final resp = await _dio.get('/api/chat/rooms/$roomId/bans');
      final data = resp.data;
      List<dynamic> list;
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'] as List;
      } else {
        list = [];
      }
      return list
          .map((e) => RoomBan.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> banUser(
      String roomId, String userId, String? reason) async {
    try {
      await _dio.post(
        '/api/chat/rooms/$roomId/bans',
        data: {
          'userId': userId,
          if (reason != null) 'reason': reason,
        },
      );
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> unbanUser(String roomId, String userId) async {
    try {
      await _dio.delete('/api/chat/rooms/$roomId/bans/$userId');
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Reports (spec section 6.3)
  // ---------------------------------------------------------------------------

  Future<int> submitReport(
      String messageId, ReportReason reason, String? comment) async {
    try {
      final resp = await _dio.post(
        '/api/chat/messages/$messageId/reports',
        data: {
          'reason': reason.apiValue,
          if (comment != null) 'comment': comment,
        },
      );
      return parseReportId(resp.data);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<List<MessageReport>> listReports(
    String roomId, {
    ReportStatus status = ReportStatus.pending,
  }) async {
    try {
      final resp = await _dio.get(
        '/api/chat/rooms/$roomId/reports',
        queryParameters: {'status': status.apiValue},
      );
      final data = resp.data;
      List<dynamic> list;
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'] as List;
      } else {
        list = [];
      }
      return list
          .map((e) => MessageReport.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<void> updateReportStatus(int reportId, ReportStatus status) async {
    try {
      await _dio.patch(
        '/api/chat/reports/$reportId',
        data: {'status': status.apiValue},
      );
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Error mapping
  // ---------------------------------------------------------------------------

  static RoomAdminApiException _mapError(DioException e) {
    final data = e.response?.data;
    String code = 'UNKNOWN';
    String message = '요청 처리 중 오류가 발생했습니다.';
    if (data is Map) {
      code = data['error']?.toString() ?? data['code']?.toString() ?? code;
      message = data['message']?.toString() ?? message;
    }
    return RoomAdminApiException(code: code, message: message);
  }
}
