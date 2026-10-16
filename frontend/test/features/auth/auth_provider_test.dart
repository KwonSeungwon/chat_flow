// ignore_for_file: deprecated_member_use
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/constants/storage_keys.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';

class _Adapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    final body = options.path == '/api/auth/login'
        ? '{"token":"t","userId":"u1","role":"DOCTOR","profileImageUrl":"/a.png"}'
        : '{}';
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

const _allKeys = [
  StorageKeys.token,
  StorageKeys.userId,
  StorageKeys.username,
  StorageKeys.role,
  StorageKeys.profileImage,
];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late int sessionEnded;
  late AuthNotifier notifier;
  late DioClient client;

  setUp(() async {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    sessionEnded = 0;
    client = DioClient()..dio.httpClientAdapter = _Adapter();
    notifier = AuthNotifier(client, onSessionEnded: () => sessionEnded++);
    await notifier.login('alice', 'Passw0rd!');
  });

  tearDown(() => notifier.dispose());

  Future<Map<String, String?>> readAll() async {
    const s = FlutterSecureStorage();
    return {for (final k in _allKeys) k: await s.read(key: k)};
  }

  test('login 직후 다섯 키가 모두 저장된다 (전제)', () async {
    expect((await readAll()).values.every((v) => v != null), isTrue);
  });

  test('logout_clearsAllFiveKeys', () async {
    await notifier.logout();

    expect((await readAll()).values.every((v) => v == null), isTrue);
    expect(notifier.debugState.token, isNull);
    expect(notifier.debugState.profileImageUrl, isNull);
    expect(sessionEnded, 1, reason: 'STOMP 등 계정 범위 리소스 정리 훅');
  });

  test('expiry_clearsProfileImage', () async {
    client.onUnauthorized!();
    await Future<void>.delayed(Duration.zero);

    final stored = await readAll();
    expect(stored[StorageKeys.profileImage], isNull);
    expect(stored[StorageKeys.role], isNull);
    expect(notifier.debugState.profileImageUrl, isNull);
    expect(sessionEnded, 1);
  });

  test('아바타 없는 계정으로 로그인하면 이전 계정의 profileImage 키를 지운다', () async {
    const s = FlutterSecureStorage();
    expect(await s.read(key: StorageKeys.profileImage), '/a.png');
    client.dio.httpClientAdapter = _NoAvatarAdapter();
    await notifier.login('bob', 'Passw0rd!');
    expect(await s.read(key: StorageKeys.profileImage), isNull);
  });

  test('copyWith(clearProfileImageUrl) 은 아바타를 null로 만든다', () {
    const s = AuthState(profileImageUrl: '/a.png');
    expect(s.copyWith().profileImageUrl, '/a.png');
    expect(s.copyWith(clearProfileImageUrl: true).profileImageUrl, isNull);
  });
}

class _NoAvatarAdapter extends _Adapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    return ResponseBody.fromString(
        '{"token":"t2","userId":"u2","role":"NURSE"}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
}
