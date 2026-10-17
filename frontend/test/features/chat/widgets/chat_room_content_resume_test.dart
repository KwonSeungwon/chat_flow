import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:chatflow/core/network/app_stomp_service.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'dart:async';

import 'package:chatflow/core/network/stomp_service.dart';
import 'package:chatflow/core/services/browser_online.dart';
import 'package:chatflow/features/chat/chat_provider.dart';
import 'package:chatflow/features/chat/widgets/chat_room_content.dart';

class _NotFoundAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async =>
      ResponseBody.fromString('{}', 404, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      });

  @override
  void close({bool force = false}) {}
}

class _CountingStomp extends StompService {
  int reconnects = 0;
  @override
  void reconnectNow() => reconnects++;
}

class _CountingAppStomp extends AppStompService {
  int reconnects = 0;
  @override
  void reconnectNow() => reconnects++;
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('app resume reconnects room and app sockets', (tester) async {
    final client = DioClient()..dio.httpClientAdapter = _NotFoundAdapter();
    final room = _CountingStomp();
    final app = _CountingAppStomp();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioClientProvider.overrideWithValue(client),
        appStompServiceProvider.overrideWithValue(app),
        chatNotifierProvider.overrideWith((ref, roomId) => ChatNotifier(client,
            ref: ref,
            token: 'tok',
            username: 'alice',
            userId: 'u1',
            stompService: room)),
      ],
      child: const MaterialApp(
        home: Scaffold(
            body: _Room()),
      ),
    ));
    await tester.pump();

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();

    expect(room.reconnects, 1);
    expect(app.reconnects, 1);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 30));
  });

  testWidgets('browser online event reconnects room and app sockets',
      (tester) async {
    final client = DioClient()..dio.httpClientAdapter = _NotFoundAdapter();
    final room = _CountingStomp();
    final app = _CountingAppStomp();
    final online = StreamController<void>.broadcast();
    addTearDown(online.close);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioClientProvider.overrideWithValue(client),
        appStompServiceProvider.overrideWithValue(app),
        browserOnlineProvider.overrideWithValue(online.stream),
        chatNotifierProvider.overrideWith((ref, roomId) => ChatNotifier(client,
            ref: ref,
            token: 'tok',
            username: 'alice',
            userId: 'u1',
            stompService: room)),
      ],
      child: const MaterialApp(home: Scaffold(body: _Room())),
    ));
    await tester.pump();

    online.add(null);
    await tester.pump();

    expect(room.reconnects, 1);
    expect(app.reconnects, 1);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 30));
  });
}

class _Room extends StatelessWidget {
  const _Room();
  @override
  Widget build(BuildContext context) =>
      ChatRoomContent(roomId: 'room-A', username: 'alice');
}
