import 'dart:async';
import 'dart:convert';

import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';
import 'package:chatflow/core/network/stomp_service.dart';

import 'fake_stomp_client.dart';

void main() {
  setUpAll(() => dotenv.testLoad(fileInput: ''));

  late List<FakeStompClient> clients;
  late List<bool> connectionEvents;
  late StompService service;

  void connect({
    void Function(String roomId, String reason)? onJoinRejected,
    void Function(String type, String message)? onTransientError,
  }) {
    service.connect(
      roomId: 'room-1',
      username: 'alice',
      userId: 'u1',
      token: 'tok',
      onMessage: (_) {},
      onConnectionChanged: connectionEvents.add,
      onJoinRejected: onJoinRejected,
      onTransientError: onTransientError,
    );
  }

  setUp(() {
    clients = [];
    connectionEvents = [];
    service = StompService(clientFactory: (config) {
      final c = FakeStompClient(config);
      clients.add(c);
      return c;
    });
  });

  test('webSocketDone_marksDisconnected', () {
    connect();
    clients.single.config.onConnect(connectedFrame());
    expect(service.isConnected, isTrue);

    clients.single.config.onWebSocketDone();

    expect(service.isConnected, isFalse);
    expect(connectionEvents, [true, false]);
    service.disconnect();
  });

  testWidgets('webSocketDone_schedulesBackoffReconnect', (tester) async {
    connect();
    clients.single.config.onConnect(connectedFrame());
    clients.single.config.onWebSocketDone();

    await tester.pump(const Duration(seconds: 2));

    expect(clients, hasLength(2));
    expect(clients.first.deactivated, isTrue);
    service.disconnect();
  });

  testWidgets('onConnect_cancelsPendingReconnect', (tester) async {
    connect();
    clients.single.config.onWebSocketError('boom');
    // A reconnect is now pending; the socket recovers before it fires.
    clients.single.config.onConnect(connectedFrame());

    await tester.pump(const Duration(seconds: 60));

    expect(clients, hasLength(1));
    service.disconnect();
  });

  testWidgets('staleClient_doneAfterReplacement_isIgnored', (tester) async {
    connect();
    final first = clients.single;
    connect(); // e.g. rejoin: replaces the client
    final second = clients.last;
    second.config.onConnect(connectedFrame());

    first.config.onWebSocketDone();
    await tester.pump(const Duration(seconds: 60));

    expect(service.isConnected, isTrue);
    expect(clients, hasLength(2));
    service.disconnect();
  });

  testWidgets('disconnectDuringTokenRefresh_doesNotReconnect', (tester) async {
    final tokenGate = Completer<String?>();
    service.connect(
      roomId: 'room-1',
      username: 'alice',
      userId: 'u1',
      token: 'tok',
      onMessage: (_) {},
      onConnectionChanged: connectionEvents.add,
      tokenProvider: () => tokenGate.future,
    );
    clients.single.config.onConnect(connectedFrame());
    clients.single.config.onWebSocketDone();
    await tester.pump(const Duration(seconds: 2)); // timer fires, awaits token

    service.disconnect(); // user left the room mid-refresh
    tokenGate.complete('fresh');
    await tester.pump();

    expect(clients, hasLength(1));
  });

  testWidgets('connect_cancelsStaleReconnectTimer', (tester) async {
    connect();
    clients.single.config.onWebSocketDone(); // reconnect pending (1s)
    connect(); // manual retry
    expect(clients, hasLength(2));

    await tester.pump(const Duration(seconds: 60));

    expect(clients, hasLength(2), reason: 'old timer must not reconnect');
    service.disconnect();
  });

  test('connectHeaders_containBearer', () {
    connect();
    final config = clients.single.config;

    expect(config.stompConnectHeaders?['Authorization'], 'Bearer tok');
    expect(config.reconnectDelay, Duration.zero);
    service.disconnect();
  });

  testWidgets('notAMember_addUser_rejectsJoinAndStopsReconnecting',
      (tester) async {
    final rejected = <String>[];
    connect(
        onJoinRejected: (roomId, reason) => rejected.add('$roomId:$reason'));
    final client = clients.single;
    client.config.onConnect(connectedFrame());

    client.callbacks['/user/queue/errors']!(StompFrame(
      command: 'MESSAGE',
      body: jsonEncode({
        'type': 'NOT_A_MEMBER',
        'operation': 'addUser',
        'roomId': 'room-1',
        'reason': 'BANNED',
      }),
    ));
    client.config.onWebSocketDone();
    await tester.pump(const Duration(seconds: 60));

    expect(rejected, ['room-1:BANNED']);
    expect(service.isConnected, isFalse);
    expect(client.deactivated, isTrue);
    expect(clients, hasLength(1));
  });

  test('notAMember_otherOperation_staysTransient', () {
    final transient = <String>[];
    final rejected = <String>[];
    connect(
      onJoinRejected: (roomId, reason) => rejected.add(reason),
      onTransientError: (type, _) => transient.add(type),
    );
    final client = clients.single;
    client.config.onConnect(connectedFrame());

    client.callbacks['/user/queue/errors']!(StompFrame(
      command: 'MESSAGE',
      body: jsonEncode({'type': 'NOT_A_MEMBER', 'operation': 'sendMessage'}),
    ));

    expect(transient, ['NOT_A_MEMBER']);
    expect(rejected, isEmpty);
    expect(service.isConnected, isTrue);
    service.disconnect();
  });

  testWidgets('retriesExhausted_keepsRetryingPeriodically', (tester) async {
    connect();
    // Every attempt fails; well past the backoff budget it must still retry.
    for (var i = 0; i < 14; i++) {
      clients.last.config.onWebSocketError('down');
      await tester.pump(const Duration(seconds: 31));
      expect(clients, hasLength(i + 2), reason: 'attempt ${i + 1}');
    }
    service.disconnect();
  });

  testWidgets('reconnectNow_whileDown_connectsImmediately', (tester) async {
    connect();
    clients.single.config.onWebSocketError('down'); // backoff pending

    service.reconnectNow(); // e.g. app resumed
    await tester.pump();

    expect(clients, hasLength(2));
    // The pending backoff timer was replaced, not doubled.
    await tester.pump(const Duration(seconds: 2));
    expect(clients, hasLength(2));
    service.disconnect();
  });

  test('reconnectNow_whenConnectedOrLeft_isNoop', () {
    connect();
    clients.single.config.onConnect(connectedFrame());
    service.reconnectNow();
    expect(clients, hasLength(1));

    service.disconnect();
    service.reconnectNow();
    expect(clients, hasLength(1));
  });

  test('connected_isReportedAfterSubscribeAndAddUser', () {
    final sentAtConnected = <String>[];
    final subscribedAtConnected = <String>[];
    service.connect(
      roomId: 'room-1',
      username: 'alice',
      userId: 'u1',
      token: 'tok',
      onMessage: (_) {},
      onConnectionChanged: (connected) {
        if (!connected) return;
        // Offline-queue flush runs here: the room must already be joined.
        sentAtConnected.addAll(clients.last.sent);
        subscribedAtConnected.addAll(clients.last.subscribed);
      },
    );
    clients.single.config.onConnect(connectedFrame());

    expect(subscribedAtConnected, contains('/topic/chat/room-1'));
    expect(sentAtConnected, contains('/app/chat.addUser'));
    service.disconnect();
  });
}
