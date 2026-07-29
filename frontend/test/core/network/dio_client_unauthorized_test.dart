import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/constants/storage_keys.dart';
import 'package:chatflow/core/network/dio_client.dart';

/// DioClient의 401 처리 계약.
///
/// DioClient는 "401이 왔다"만 알린다 — 세션이 실제로 있었는지 판단하지 않는다.
/// storage는 근거로 못 쓴다: 다른 탭이 로그아웃하면 (web은 localStorage 공유)
/// storage만 비고 이 탭의 in-memory state는 살아 있다. 그때도 리다이렉트는
/// 일어나야 하므로 판단은 구독자(AuthNotifier)가 자기 state를 보고 한다.
/// → auth_unauthorized_guard_test.dart
class _StatusAdapter implements HttpClientAdapter {
  _StatusAdapter(this.status);

  final int status;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    return ResponseBody.fromString('{}', status, headers: {
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
  });

  /// 401을 한 번 태우고 onUnauthorized 호출 여부를 돌려준다.
  Future<bool> fireUnauthorized({
    required String path,
    required Map<String, String> storage,
  }) async {
    FlutterSecureStorage.setMockInitialValues(
        Map<String, String>.from(storage));
    final client = DioClient();
    client.dio.httpClientAdapter = _StatusAdapter(401);

    var called = false;
    client.onUnauthorized = () => called = true;

    await expectLater(client.dio.post(path), throwsA(isA<DioException>()));
    return called;
  }

  group('DioClient 401 처리', () {
    test('저장된 토큰이 있으면 onUnauthorized를 호출한다 — 세션 만료', () async {
      final called = await fireUnauthorized(
        path: '/api/files/upload',
        storage: {StorageKeys.token: 'expired-token'},
      );

      expect(called, isTrue);
    });

    test('저장된 토큰이 없어도 onUnauthorized를 호출한다 — 판단은 구독자 몫', () async {
      // 다른 탭이 로그아웃해 storage만 비어 있어도 이 탭은 리다이렉트돼야 한다.
      final called = await fireUnauthorized(
        path: '/api/files/upload',
        storage: {},
      );

      expect(called, isTrue);
    });

    test('만료된 세션의 토큰은 저장소에서 지운다', () async {
      FlutterSecureStorage.setMockInitialValues({
        StorageKeys.token: 'expired-token',
        StorageKeys.userId: 'u1',
        StorageKeys.username: 'alice',
      });
      final client = DioClient();
      client.dio.httpClientAdapter = _StatusAdapter(401);

      await expectLater(
          client.dio.post('/api/chat/rooms'), throwsA(isA<DioException>()));

      const storage = FlutterSecureStorage();
      expect(await storage.read(key: StorageKeys.token), isNull);
      expect(await storage.read(key: StorageKeys.userId), isNull);
    });

    test('/api/auth/* 의 401은 자격증명 오류라 세션을 건드리지 않는다', () async {
      final called = await fireUnauthorized(
        path: '/api/auth/login',
        storage: {StorageKeys.token: 'some-token'},
      );

      expect(called, isFalse);
    });
  });
}
