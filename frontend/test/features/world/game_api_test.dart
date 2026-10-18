import 'dart:convert';
import 'dart:typed_data';

import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

/// 경로별로 준비한 (status, body)를 돌려주는 가짜 어댑터.
class _FakeAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  final bodies = <String, Object?>{};
  final routes = <String, (int, Object?)>{};
  bool offline = false;
  DioExceptionType? timeout;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    if (requestStream != null) {
      final bytes = await requestStream.expand((c) => c).toList();
      bodies['${options.method} ${options.path}'] =
          jsonDecode(utf8.decode(bytes));
    }
    if (timeout != null) {
      throw DioException(requestOptions: options, type: timeout!);
    }
    if (offline) {
      throw DioException.connectionError(
          requestOptions: options, reason: 'offline');
    }
    final (status, body) = routes['${options.method} ${options.path}'] ??
        (404, {'status': 404, 'code': 'NOT_FOUND', 'message': 'x'});
    return ResponseBody.fromString(jsonEncode(body), status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

Map<String, dynamic> _ok(Object data) => {'success': true, 'data': data};

void main() {
  late _FakeAdapter adapter;
  late GameApi api;

  setUp(() {
    adapter = _FakeAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'))
      ..httpClientAdapter = adapter;
    api = GameApi(dio);
  });

  test('listDungeons parses envelope', () async {
    adapter.routes['GET /api/game/dungeons'] = (
      200,
      _ok([
        {
          'id': 'k8s',
          'name': '쿠버네티스 성',
          'buildingStyle': 'k8s',
          'open': true,
          'clearedByMe': false,
          'clearCount': 3
        }
      ])
    );
    final list = await api.listDungeons();
    expect(list.single.id, 'k8s');
    expect(list.single.open, isTrue);
    expect(list.single.clearCount, 3);
  });

  test('startRun / next / answer parse and send body', () async {
    adapter.routes['POST /api/game/dungeons/jvm/runs'] = (
      200,
      _ok({'runId': 'r1', 'hearts': 3, 'kills': 0, 'killsToBoss': 5})
    );
    adapter.routes['GET /api/game/runs/r1/next'] = (
      200,
      _ok({
        'seq': 2,
        'monster': {'name': 'OOM 슬라임', 'spriteKey': 'monster_jvm', 'isBoss': false},
        'question': {
          'id': 9,
          'stem': 'Q?',
          'choices': ['a', 'b', 'c', 'd'],
          'topicTitle': 'JVM 힙'
        }
      })
    );
    adapter.routes['POST /api/game/runs/r1/answers'] = (
      200,
      _ok({
        'correct': false,
        'correctIndex': 1,
        'explanation': 'because',
        'topicTitle': 'JVM 힙',
        'sourceRef': 'wiki/jvm.md',
        'hearts': 2,
        'kills': 0,
        'bossUnlocked': false,
        'cleared': false,
        'failed': false
      })
    );

    final run = await api.startRun('jvm');
    expect(run.runId, 'r1');
    expect(run.killsToBoss, 5);

    final enc = await api.next('r1');
    expect(enc.seq, 2);
    expect(enc.monster.spriteKey, 'monster_jvm');
    expect(enc.question.choices, hasLength(4));

    final r = await api.answer('r1', 2, 3);
    expect(adapter.bodies['POST /api/game/runs/r1/answers'],
        {'seq': 2, 'choiceIndex': 3});
    expect(r.correct, isFalse);
    expect(r.correctIndex, 1);
    expect(r.sourceRef, 'wiki/jvm.md');
    expect(r.hearts, 2);
  });

  test('me and leaderboard', () async {
    adapter.routes['GET /api/game/me'] =
        (200, _ok({'clearedCount': 8, 'total': 8, 'graduated': true}));
    adapter.routes['GET /api/game/leaderboard'] = (
      200,
      _ok([
        {'username': 'alice', 'clearedCount': 4, 'correctCount': 30}
      ])
    );
    expect((await api.me()).graduated, isTrue);
    expect((await api.leaderboard()).single.username, 'alice');
  });

  Future<void> expectCode(Future<Object?> f, String code) => expectLater(
      f, throwsA(isA<GameApiException>().having((e) => e.code, 'code', code)));

  test('error codes map to GameApiException', () async {
    adapter.routes['POST /api/game/dungeons/db/runs'] = (
      409,
      {'status': 409, 'code': 'DUNGEON_CLOSED', 'message': 'closed'}
    );
    adapter.routes['GET /api/game/runs/gone/next'] = (
      404,
      {'status': 404, 'code': 'RUN_EXPIRED', 'message': 'gone'}
    );
    adapter.routes['POST /api/game/runs/r1/answers'] = (
      409,
      {'status': 409, 'code': 'SEQ_MISMATCH', 'message': 'seq'}
    );
    adapter.routes['GET /api/game/me'] = (
      503,
      {'status': 503, 'code': 'GAME_UNAVAILABLE', 'message': 'redis'}
    );

    await expectCode(api.startRun('db'), GameApiException.dungeonClosed);
    await expectCode(api.next('gone'), GameApiException.runExpired);
    await expectCode(api.answer('r1', 1, 0), GameApiException.seqMismatch);
    await expectCode(api.me(), GameApiException.unavailable);
  });

  test('gateway 503 SERVICE_UNAVAILABLE maps to GAME_UNAVAILABLE', () async {
    adapter.routes['GET /api/game/dungeons'] = (
      503,
      {'status': 503, 'code': 'SERVICE_UNAVAILABLE', 'message': 'circuit open'}
    );
    adapter.routes['GET /api/game/me'] = (503, 'Service Unavailable');
    await expectCode(api.listDungeons(), GameApiException.unavailable);
    await expectCode(api.me(), GameApiException.unavailable);
  });

  test('me.admin is read, missing means false (old server)', () async {
    adapter.routes['GET /api/game/me'] = (
      200,
      _ok({'clearedCount': 1, 'total': 8, 'graduated': false, 'admin': true})
    );
    expect((await api.me()).admin, isTrue);
    adapter.routes['GET /api/game/me'] =
        (200, _ok({'clearedCount': 1, 'total': 8, 'graduated': false}));
    expect((await api.me()).admin, isFalse);
  });

  test('connection error maps to OFFLINE', () async {
    adapter.offline = true;
    await expectCode(api.next('r1'), GameApiException.offline);
  });

  test('send/receive timeouts map to OFFLINE', () async {
    for (final t in [
      DioExceptionType.sendTimeout,
      DioExceptionType.receiveTimeout,
    ]) {
      adapter.timeout = t;
      await expectCode(api.answer('r1', 1, 0), GameApiException.offline);
    }
  });
}
