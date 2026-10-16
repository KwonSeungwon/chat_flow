import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';
import 'package:chatflow/features/chat/bookmark_provider.dart';
import 'package:chatflow/features/chat/notification_policy_provider.dart';
import 'package:chatflow/features/chat/room_keywords_provider.dart';
import 'package:chatflow/features/chat/scheduled_messages_provider.dart';

class _Adapter implements HttpClientAdapter {
  int scheduledCalls = 0;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    String body = '[]';
    if (options.path == '/api/auth/login') {
      final u = (options.data as Map)['username'];
      body = '{"token":"t-$u","userId":"$u","role":"NURSE"}';
    } else if (options.path.startsWith('/api/chat/scheduled-messages')) {
      scheduledCalls++;
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
      Future<void>.delayed(const Duration(milliseconds: 30));

  Future<void> loginAs(String u) async {
    await container.read(authProvider.notifier).login(u, 'Passw0rd!');
    await settle();
  }

  test('scheduledMessages refetches on account switch', () async {
    final sub = container.listen(scheduledMessagesProvider, (_, __) {});
    addTearDown(sub.close);
    await loginAs('alice');
    final afterAlice = adapter.scheduledCalls;
    await loginAs('bob');
    expect(adapter.scheduledCalls, greaterThan(afterAlice));
  });

  test('bookmarks: B does not see A, and A gets own back', () async {
    final sub = container.listen(bookmarksProvider, (_, __) {});
    addTearDown(sub.close);
    await loginAs('alice');
    await container.read(bookmarksProvider.notifier).add(BookmarkEntry(
        messageId: 'm1',
        roomId: 'r',
        username: 'a',
        content: 'c',
        timestamp: 't'));
    expect(container.read(bookmarksProvider), hasLength(1));

    await loginAs('bob');
    expect(container.read(bookmarksProvider), isEmpty);

    await loginAs('alice');
    expect(container.read(bookmarksProvider).single.messageId, 'm1');
  });

  test('keywords: B does not see A, A restored', () async {
    final sub = container.listen(roomKeywordsProvider, (_, __) {});
    addTearDown(sub.close);
    await loginAs('alice');
    await container
        .read(roomKeywordsProvider.notifier)
        .setKeywords('r1', ['urgent']);

    await loginAs('bob');
    expect(container.read(roomKeywordsProvider), isEmpty);

    await loginAs('alice');
    expect(container.read(roomKeywordsProvider)['r1'], ['urgent']);
  });

  test('notification policy: B does not inherit A mute', () async {
    final sub = container.listen(roomNotificationPolicyProvider, (_, __) {});
    addTearDown(sub.close);
    await loginAs('alice');
    await container
        .read(roomNotificationPolicyProvider.notifier)
        .setPolicy('r1', NotificationPolicy.muted);

    await loginAs('bob');
    expect(container.read(roomNotificationPolicyProvider), isEmpty);

    await loginAs('alice');
    expect(container.read(roomNotificationPolicyProvider)['r1'],
        NotificationPolicy.muted);
  });

  test('logged out (userId null): nothing is loaded or saved', () async {
    FlutterSecureStorage.setMockInitialValues({
      'chatflow.bookmarks': '[{"messageId":"old"}]',
      'chatflow.roomKeywords': '{"r":["x"]}',
    });
    final sub1 = container.listen(bookmarksProvider, (_, __) {});
    final sub2 = container.listen(roomKeywordsProvider, (_, __) {});
    addTearDown(sub1.close);
    addTearDown(sub2.close);
    await settle();
    expect(container.read(bookmarksProvider), isEmpty);
    expect(container.read(roomKeywordsProvider), isEmpty);

    await container.read(roomKeywordsProvider.notifier).setKeywords('r', ['y']);
    final all = await const FlutterSecureStorage().readAll();
    expect(all.keys.where((k) => k.startsWith('chatflow.roomKeywords')),
        ['chatflow.roomKeywords'],
        reason: 'legacy key untouched, nothing new written');
    expect(all['chatflow.roomKeywords'], '{"r":["x"]}');
  });
}
