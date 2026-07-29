import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';

/// 401 → 강제 로그아웃은 **세션이 있었을 때만** 일어나야 한다.
///
/// 세션이 없는데 리셋하면 state.error가 통째로 날아간다. prod에서 이게
/// "회원가입 버튼이 아무 반응 없다"로 보였다: 가입 실패(400) 직후 아바타 업로드가
/// 인증 없이 나가 401을 받았고, 그 401이 방금 띄운 실패 사유를 지웠다.
///
/// 반대로 세션이 있으면 storage 상태와 무관하게 반드시 리셋해야 한다 —
/// 다른 탭이 로그아웃해 storage만 빈 경우에도 이 탭은 로그인 화면으로 나가야 한다.
class _ScriptedAdapter implements HttpClientAdapter {
  _ScriptedAdapter(this.responses);

  /// path(정확히 일치) → (status, body). 등록되지 않은 경로는 404.
  final Map<String, (int, String)> responses;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    final (status, body) = responses[options.path] ?? (404, '{}');
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  /// AuthNotifier가 생성자에서 콜백을 걸어두는 그 DioClient를 함께 돌려준다 —
  /// 테스트는 이 client의 onUnauthorized를 직접 발화시켜 401을 흉내낸다.
  (AuthNotifier, DioClient) notifierWith(Map<String, (int, String)> responses) {
    final client = DioClient()
      ..dio.httpClientAdapter = _ScriptedAdapter(responses);
    return (AuthNotifier(client), client);
  }

  test('세션이 없으면 401이 와도 state를 리셋하지 않는다 — 실패 사유 보존', () async {
    final (notifier, client) =
        notifierWith({'/api/auth/register': (400, '{}')});
    addTearDown(notifier.dispose);

    await notifier.register('taken', 'Passw0rd!');
    expect(notifier.debugState.error, '이미 사용 중인 아이디입니다.');

    // 가입 실패 직후 아바타 업로드가 받아온 401.
    client.onUnauthorized!();

    expect(notifier.debugState.error, '이미 사용 중인 아이디입니다.',
        reason: '세션이 없었으므로 로그아웃시킬 것도, 지울 에러도 없다');
  });

  test('세션이 있으면 401에 state를 리셋한다 — 강제 로그아웃', () async {
    final (notifier, client) = notifierWith({
      '/api/auth/login': (200, '{"token":"t","userId":"u1","role":"NURSE"}'),
    });
    addTearDown(notifier.dispose);

    await notifier.login('alice', 'Passw0rd!');
    expect(notifier.debugState.token, 't');

    // 다른 탭이 로그아웃해 storage가 이미 비었어도 이 탭은 나가야 한다.
    FlutterSecureStorage.setMockInitialValues({});
    client.onUnauthorized!();

    expect(notifier.debugState.token, isNull);
    expect(notifier.debugState.isHydrated, isTrue);
  });
}
