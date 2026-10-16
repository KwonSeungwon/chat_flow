import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';
import 'package:chatflow/features/profile/profile_provider.dart';

/// 로그인은 userId=요청 username, /api/users/me는 호출 시점의 로그인 사용자 이름을 돌려준다.
class _Adapter implements HttpClientAdapter {
  String current = '';
  int meCalls = 0;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    String body = '{}';
    if (options.path == '/api/auth/login') {
      current = (options.data as Map)['username'] as String;
      body = '{"token":"t-$current","userId":"$current","role":"NURSE"}';
    } else if (options.path == '/api/users/me') {
      meCalls++;
      body = '{"id":"$current","username":"$current",'
          '"profileImageUrl":"/img-$current.png"}';
    }
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _Adapter adapter;
  late ProviderContainer container;

  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    adapter = _Adapter();
    final client = DioClient()..dio.httpClientAdapter = adapter;
    container = ProviderContainer(
        overrides: [dioClientProvider.overrideWithValue(client)]);
    addTearDown(container.dispose);
  });

  Future<void> settle() =>
      Future<void>.delayed(const Duration(milliseconds: 20));

  test('userChange_refetches', () async {
    final sub = container.listen(profileProvider, (_, __) {});
    addTearDown(sub.close);
    final auth = container.read(authProvider.notifier);

    await auth.login('alice', 'Passw0rd!');
    await settle();
    expect(container.read(profileProvider).value?.username, 'alice');
    final callsAfterAlice = adapter.meCalls;

    await auth.login('bob', 'Passw0rd!');
    await settle();

    expect(adapter.meCalls, greaterThan(callsAfterAlice));
    expect(container.read(profileProvider).value?.username, 'bob');
  });

  test('update() 가 새 아바타를 authProvider에 반영한다', () async {
    final auth = container.read(authProvider.notifier);
    await auth.login('alice', 'Passw0rd!');
    await auth.applyProfileImage('/old.png');
    final sub = container.listen(profileProvider, (_, __) {});
    addTearDown(sub.close);
    await settle();
    // getMe 응답(/img-alice.png)이 아니라 update 응답이 auth로 흘러야 한다.
    adapter.current = 'alice';
    await container.read(profileProvider.notifier).update(statusMessage: 'hi');
    await settle();
    expect(container.read(authProvider).profileImageUrl, '/img-alice.png');
  });
}
