import 'dart:typed_data';

import 'package:stomp_dart_client/stomp_dart_client.dart';

/// Records what a STOMP service does with its client; never opens a socket.
class FakeStompClient implements StompClient {
  FakeStompClient(this.config);

  @override
  final StompConfig config;

  bool activated = false;
  bool deactivated = false;
  final List<String> subscribed = [];
  final Map<String, StompFrameCallback> callbacks = {};
  final List<String> sent = [];

  @override
  void activate() => activated = true;

  @override
  void deactivate() => deactivated = true;

  @override
  StompUnsubscribe subscribe({
    required String destination,
    required StompFrameCallback callback,
    Map<String, String>? headers,
  }) {
    subscribed.add(destination);
    callbacks[destination] = callback;
    return ({Map<String, String>? unsubscribeHeaders}) {};
  }

  @override
  void send({
    required String destination,
    Map<String, String>? headers,
    String? body,
    Uint8List? binaryBody,
  }) =>
      sent.add(destination);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

StompFrame connectedFrame() => StompFrame(command: 'CONNECTED');
