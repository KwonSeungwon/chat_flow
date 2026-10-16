import 'dart:convert';

import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';
import 'package:chatflow/core/network/app_stomp_service.dart';

import 'fake_stomp_client.dart';

void main() {
  setUpAll(() => dotenv.testLoad(fileInput: ''));

  late List<FakeStompClient> clients;
  late AppStompService service;

  setUp(() {
    clients = [];
    service = AppStompService(clientFactory: (config) {
      final c = FakeStompClient(config);
      clients.add(c);
      return c;
    });
  });

  tearDown(() => service.disconnect());

  void deliverRoomUpdate(String roomId) {
    clients.last.callbacks['/user/queue/room-updates']!(StompFrame(
      command: 'MESSAGE',
      body: jsonEncode({'type': 'UNREAD_INCREMENT', 'roomId': roomId}),
    ));
  }

  test('connectHeaders_containBearer', () {
    service.connect(
        userId: 'u1',
        token: 'tok',
        onRoomUpdate: (_, __,
            {mentionedUsernames = const [],
            content = '',
            senderUsername = ''}) {});

    final config = clients.single.config;
    expect(config.stompConnectHeaders?['Authorization'], 'Bearer tok');
    expect(config.reconnectDelay, Duration.zero);
  });

  test('connect_whenAlreadyConnected_replacesCallback', () {
    final first = <String>[];
    final second = <String>[];
    service.connect(
        userId: 'u1',
        token: 'tok',
        onRoomUpdate: (roomId, _,
                {mentionedUsernames = const [],
                content = '',
                senderUsername = ''}) =>
            first.add(roomId));
    clients.single.config.onConnect(connectedFrame());

    service.connect(
        userId: 'u1',
        token: 'tok',
        onRoomUpdate: (roomId, _,
                {mentionedUsernames = const [],
                content = '',
                senderUsername = ''}) =>
            second.add(roomId));
    deliverRoomUpdate('r1');

    expect(clients, hasLength(1), reason: 'live socket is reused');
    expect(first, isEmpty);
    expect(second, ['r1']);
  });

  testWidgets('onConnect_cancelsPendingReconnect', (tester) async {
    service.connect(
        userId: 'u1',
        token: 'tok',
        onRoomUpdate: (_, __,
            {mentionedUsernames = const [],
            content = '',
            senderUsername = ''}) {});
    clients.single.config.onWebSocketError('boom');
    // A reconnect is now pending; the socket recovers before it fires.
    clients.single.config.onConnect(connectedFrame());

    await tester.pump(const Duration(seconds: 120));

    expect(clients, hasLength(1));
    service.disconnect();
  });

  test('webSocketDone_marksDisconnected', () {
    service.connect(
        userId: 'u1',
        token: 'tok',
        onRoomUpdate: (_, __,
            {mentionedUsernames = const [],
            content = '',
            senderUsername = ''}) {});
    clients.single.config.onConnect(connectedFrame());

    clients.single.config.onWebSocketDone();

    expect(service.isConnected, isFalse);
  });

  void connectApp() => service.connect(
      userId: 'u1',
      token: 'tok',
      onRoomUpdate: (_, __,
          {mentionedUsernames = const [],
          content = '',
          senderUsername = ''}) {});

  testWidgets('retriesExhausted_keepsRetryingPeriodically', (tester) async {
    connectApp();
    for (var i = 0; i < 14; i++) {
      clients.last.config.onWebSocketError('down');
      await tester.pump(const Duration(seconds: 61));
      expect(clients, hasLength(i + 2), reason: 'attempt ${i + 1}');
    }
  });

  testWidgets('reconnectNow_whileDown_connectsImmediately', (tester) async {
    connectApp();
    clients.single.config.onWebSocketError('down');

    service.reconnectNow();
    expect(clients, hasLength(2));
    await tester.pump(const Duration(seconds: 3));
    expect(clients, hasLength(2));
  });

  test('reconnectNow_whenConnectedOrLoggedOut_isNoop', () {
    connectApp();
    clients.single.config.onConnect(connectedFrame());
    service.reconnectNow();
    expect(clients, hasLength(1));

    service.disconnect();
    service.reconnectNow();
    expect(clients, hasLength(1));
  });
}
