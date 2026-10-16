import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/search/search_provider.dart';

/// query별로 응답 시점을 테스트가 직접 쥔다.
class _GatedAdapter implements HttpClientAdapter {
  final gates = <String, Completer<void>>{};

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    final q = options.queryParameters['query'] as String;
    await gates.putIfAbsent(q, Completer<void>.new).future;
    return ResponseBody.fromString(
        '{"content":[{"id":"1","content":"$q","chatRoomId":"r"}],"totalElements":1}',
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('slowFirstResponse_doesNotOverwriteSecond', () async {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    final adapter = _GatedAdapter();
    final client = DioClient()..dio.httpClientAdapter = adapter;
    final container = ProviderContainer(
        overrides: [dioClientProvider.overrideWithValue(client)]);
    addTearDown(container.dispose);
    final sub = container.listen(searchProvider, (_, __) {});
    addTearDown(sub.close);

    final notifier = container.read(searchProvider.notifier);
    final first = notifier.search('first');
    final second = notifier.search('second');
    await Future<void>.delayed(const Duration(milliseconds: 20));

    adapter.gates['second']!.complete();
    await second;
    expect(container.read(searchProvider).results.single.content, 'second');

    adapter.gates['first']!.complete();
    await first;

    final s = container.read(searchProvider);
    expect(s.results.single.content, 'second',
        reason: '늦게 도착한 이전 응답이 최신 결과를 덮으면 안 된다');
    expect(s.isLoading, isFalse);
  });
}
