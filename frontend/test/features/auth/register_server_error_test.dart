import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';

/// /api/auth/register answers [status] with [body].
class _Adapter implements HttpClientAdapter {
  _Adapter(this.status, this.body);
  final int status;
  final Object body;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async =>
      ResponseBody.fromString(jsonEncode(body), status, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      });

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  Future<String?> registerError(int status, Object body) async {
    final client = DioClient()..dio.httpClientAdapter = _Adapter(status, body);
    final notifier = AuthNotifier(client);
    addTearDown(notifier.dispose);
    await notifier.register('newbie', 'Passw0rd!');
    late AuthState s;
    notifier.addListener((v) => s = v);
    return s.error;
  }

  test('400 with server error message shows that message', () async {
    expect(await registerError(400, {'error': '아이디는 2~20자로 입력해 주세요.'}),
        '아이디는 2~20자로 입력해 주세요.');
  });

  test('400 without message falls back to duplicate text', () async {
    expect(await registerError(400, {}), '이미 사용 중인 아이디입니다.');
  });

  test('409 (duplicate race) shows duplicate text', () async {
    expect(await registerError(409, {'error': ''}), '이미 사용 중인 아이디입니다.');
  });

  test('409 with server message shows that message', () async {
    expect(
        await registerError(
            409, {'error': '같은 아이디로 가입이 진행 중입니다. 잠시 후 다시 시도해 주세요.'}),
        '같은 아이디로 가입이 진행 중입니다. 잠시 후 다시 시도해 주세요.');
  });

  test('409 without body message falls back to duplicate text', () async {
    expect(await registerError(409, {}), '이미 사용 중인 아이디입니다.');
  });
}
