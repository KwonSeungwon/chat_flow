import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_response.dart';
import '../../../core/network/dio_client.dart';
import 'game_models.dart';

/// 게임 API 오류. [code]는 서버 오류 코드(`RUN_EXPIRED`, `DUNGEON_CLOSED`,
/// `SEQ_MISMATCH`, `ANSWER_IN_PROGRESS`, `GAME_UNAVAILABLE` 등)이거나
/// 클라이언트가 붙인 `OFFLINE`(연결 실패), `PARSE_ERROR`, `UNKNOWN`이다.
class GameApiException implements Exception {
  static const runExpired = 'RUN_EXPIRED';
  static const dungeonClosed = 'DUNGEON_CLOSED';
  static const seqMismatch = 'SEQ_MISMATCH';
  static const answerInProgress = 'ANSWER_IN_PROGRESS';
  static const unavailable = 'GAME_UNAVAILABLE';
  static const offline = 'OFFLINE';

  final String code;
  final int? statusCode;
  final String message;

  GameApiException(this.code, {this.statusCode, this.message = ''});

  @override
  String toString() => 'GameApiException($code): $message';
}

class GameApi {
  final Dio _dio;

  GameApi(this._dio);

  Future<List<DungeonInfo>> listDungeons() => _call(() async {
        final resp = await _dio.get('/api/game/dungeons');
        return apiResponseList(resp.data)
            .map((e) => DungeonInfo.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
      });

  Future<RunStart> startRun(String dungeonId) => _call(() async {
        final resp = await _dio.post('/api/game/dungeons/$dungeonId/runs');
        return RunStart.fromJson(_map(resp.data));
      });

  Future<Encounter> next(String runId) => _call(() async {
        final resp = await _dio.get('/api/game/runs/$runId/next');
        return Encounter.fromJson(_map(resp.data));
      });

  Future<AnswerResult> answer(String runId, int seq, int choiceIndex) =>
      _call(() async {
        final resp = await _dio.post('/api/game/runs/$runId/answers',
            data: {'seq': seq, 'choiceIndex': choiceIndex});
        return AnswerResult.fromJson(_map(resp.data));
      });

  Future<GameMe> me() => _call(() async {
        final resp = await _dio.get('/api/game/me');
        return GameMe.fromJson(_map(resp.data));
      });

  Future<void> setAvatar(StudentLook look) => _call(() async {
        await _dio.put('/api/game/me/avatar', data: {'avatar': look.name});
      });

  Future<List<LeaderboardEntry>> leaderboard() => _call(() async {
        final resp = await _dio.get('/api/game/leaderboard');
        return apiResponseList(resp.data)
            .map((e) =>
                LeaderboardEntry.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
      });

  static Map<String, dynamic> _map(Object? payload) {
    final map = apiResponseMap(payload);
    if (map == null) throw const FormatException('expected object');
    return map;
  }

  static Future<T> _call<T>(Future<T> Function() body) async {
    try {
      return await body();
    } on DioException catch (e) {
      throw _mapError(e);
    } on TypeError catch (_) {
      throw GameApiException('PARSE_ERROR', message: '응답 형식 오류');
    } on FormatException catch (_) {
      throw GameApiException('PARSE_ERROR', message: '응답 형식 오류');
    }
  }

  static GameApiException _mapError(DioException e) {
    if (e.type == DioExceptionType.connectionError ||
        e.type == DioExceptionType.connectionTimeout ||
        e.type == DioExceptionType.sendTimeout ||
        e.type == DioExceptionType.receiveTimeout) {
      return GameApiException(GameApiException.offline,
          message: '서버에 연결할 수 없습니다.');
    }
    final data = e.response?.data;
    var code = 'UNKNOWN';
    var message = '';
    if (data is Map) {
      code = data['code']?.toString() ?? code;
      message = data['message']?.toString() ?? message;
    }
    // Gateway circuit/timeout fallback answers 503 SERVICE_UNAVAILABLE (or a
    // non-JSON body): same "the game is down" notice as GAME_UNAVAILABLE.
    if (e.response?.statusCode == 503) code = GameApiException.unavailable;
    return GameApiException(code,
        statusCode: e.response?.statusCode, message: message);
  }
}

final gameApiProvider = Provider<GameApi>((ref) {
  return GameApi(ref.watch(dioClientProvider).dio);
});
