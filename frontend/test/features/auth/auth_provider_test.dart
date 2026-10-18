// ignore_for_file: deprecated_member_use
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/core/constants/storage_keys.dart';
import 'package:gossiplog/core/network/dio_client.dart';
import 'package:gossiplog/features/auth/auth_provider.dart';

class _Adapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    // 롤링 업데이트 중 옛 gateway가 role을 계속 내려줄 수 있다 — 클라이언트는 무시해야 한다.
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

  test('login 직후 네 키가 모두 저장된다 (전제)', () async {
    expect((await readAll()).values.every((v) => v != null), isTrue);
  });

  test('login은 응답에 role이 있어도 직군 키를 저장하지 않는다', () async {
    const s = FlutterSecureStorage();
    expect(await s.read(key: StorageKeys.legacyRole), isNull);
  });

  test('logout_clearsAllKeysIncludingLegacyRole', () async {
    const s = FlutterSecureStorage();
    // 예전 버전이 남긴 직군 키 — 로그아웃 때 함께 지워져야 한다.
    await s.write(key: StorageKeys.legacyRole, value: 'NURSE');

    await notifier.logout();

    expect((await readAll()).values.every((v) => v == null), isTrue);
    expect(await s.read(key: StorageKeys.legacyRole), isNull);
    expect(notifier.debugState.token, isNull);
    expect(notifier.debugState.profileImageUrl, isNull);
    expect(sessionEnded, 1, reason: 'STOMP 등 계정 범위 리소스 정리 훅');
  });

  test('expiry_clearsProfileImage', () async {
    client.onUnauthorized!();
    await Future<void>.delayed(Duration.zero);

    final stored = await readAll();
    expect(stored[StorageKeys.profileImage], isNull);
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

  test('비밀번호 변경 응답에 token이 있으면 저장 토큰과 상태를 새 토큰으로 바꾼다', () async {
    client.dio.httpClientAdapter = _PasswordAdapter(
        '{"message":"비밀번호가 변경되었습니다.","token":"t-new"}');
    await notifier.changePassword('Passw0rd!', 'NewPassw0rd!');
    const s = FlutterSecureStorage();
    expect(await s.read(key: StorageKeys.token), 't-new');
    expect(notifier.debugState.token, 't-new');
    expect(notifier.debugState.userId, 'u1');
    expect(notifier.debugState.username, 'alice');
    expect(notifier.debugState.profileImageUrl, '/a.png');
  });

  test('envelope 안의 token도 읽는다', () async {
    client.dio.httpClientAdapter =
        _PasswordAdapter('{"success":true,"data":{"token":"t-env"}}');
    await notifier.changePassword('Passw0rd!', 'NewPassw0rd!');
    expect(notifier.debugState.token, 't-env');
  });

  test('새 토큰 저장이 실패하면 변경은 성공으로 두고 깨끗이 로그아웃한다', () async {
    final storage = _FailingTokenWriteStorage();
    final n = AuthNotifier(client,
        onSessionEnded: () => sessionEnded++, storage: storage);
    addTearDown(n.dispose);
    await n.login('alice', 'Passw0rd!');
    storage.failTokenWrite = true;
    client.dio.httpClientAdapter = _PasswordAdapter(
        '{"message":"비밀번호가 변경되었습니다.","token":"t-new"}');

    final result = await n.changePassword('Passw0rd!', 'NewPassw0rd!');

    expect(result, PasswordChangeResult.reloginRequired);
    expect(n.debugState.token, isNull);
    expect(n.debugState.loggedOut, isTrue);
    expect(sessionEnded, 1);
    expect((await readAll()).values.every((v) => v == null), isTrue);
  });

  test('정상 교체는 changed를 돌려준다', () async {
    client.dio.httpClientAdapter =
        _PasswordAdapter('{"message":"ok","token":"t-new"}');
    expect(await notifier.changePassword('Passw0rd!', 'NewPassw0rd!'),
        PasswordChangeResult.changed);
  });

  test('옛 서버(token 없음)면 기존 토큰을 유지한다', () async {
    client.dio.httpClientAdapter =
        _PasswordAdapter('{"message":"비밀번호가 변경되었습니다."}');
    await notifier.changePassword('Passw0rd!', 'NewPassw0rd!');
    const s = FlutterSecureStorage();
    expect(await s.read(key: StorageKeys.token), 't');
    expect(notifier.debugState.token, 't');
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
        '{"token":"t2","userId":"u2"}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
}

class _PasswordAdapter extends _Adapter {
  _PasswordAdapter(this.body);
  final String body;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
}

class _FailingTokenWriteStorage extends FlutterSecureStorage {
  bool failTokenWrite = false;

  @override
  Future<void> write({
    required String key,
    required String? value,
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) {
    if (failTokenWrite && key == StorageKeys.token) {
      throw Exception('secure storage write failed');
    }
    return super.write(key: key, value: value);
  }
}
