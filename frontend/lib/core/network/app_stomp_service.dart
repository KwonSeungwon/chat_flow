import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:stomp_dart_client/stomp_dart_client.dart';

import 'stomp_support.dart';

typedef RoomUpdateCallback = void Function(
  String roomId,
  String type, {
  List<String> mentionedUsernames,
  String content,
  String senderUsername,
});

/// App-level single STOMP connection for global subscriptions
/// (e.g. /user/queue/room-updates for unread increments).
/// Separate from per-room StompService to avoid lifecycle coupling.
class AppStompService {
  AppStompService({StompClient Function(StompConfig config)? clientFactory})
      : _clientFactory =
            clientFactory ?? ((config) => StompClient(config: config));

  final StompClient Function(StompConfig config) _clientFactory;
  StompClient? _client;
  bool _connected = false;
  bool _manualDisconnect = false;
  Timer? _reconnectTimer;
  int _retryCount = 0;
  static const int _maxRetries = 10;

  String? _userId;
  String? _token;
  RoomUpdateCallback? _onRoomUpdate;

  bool get isConnected => _connected;

  void connect({
    required String userId,
    required String token,
    required RoomUpdateCallback onRoomUpdate,
  }) {
    if (_connected && _userId == userId) {
      // Same user, live socket: keep it, but the latest caller's callback
      // and token win (the previous owner may be gone).
      _onRoomUpdate = onRoomUpdate;
      _token = token;
      return;
    }
    disconnect();
    _userId = userId;
    _token = token;
    _onRoomUpdate = onRoomUpdate;
    _manualDisconnect = false;
    _retryCount = 0;
    _doConnect(token);
  }

  void _doConnect(String token) {
    _client?.deactivate();

    final url = resolveStompWsUrl();

    // Callbacks are bound to this client instance so a deactivated client's
    // late onWebSocketDone cannot schedule a reconnect over its replacement.
    late final StompClient client;
    bool isCurrent() => identical(_client, client);
    client = _clientFactory(
      StompConfig(
        url: url,
        // Reconnects are owned by _scheduleReconnect (backoff).
        reconnectDelay: Duration.zero,
        // Browsers cannot set WS handshake headers; the STOMP CONNECT frame
        // header is the auth path the server requires on every platform.
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
        onConnect: (frame) {
          if (isCurrent()) _onConnect(frame);
        },
        onDisconnect: (_) {
          if (isCurrent()) _onDisconnect();
        },
        onWebSocketDone: () {
          if (isCurrent()) _onDisconnect();
        },
        onStompError: (f) => debugPrint('[AppSTOMP] Error: ${f.body}'),
        onWebSocketError: (e) {
          debugPrint('[AppSTOMP] WS error: $e');
          if (isCurrent()) _onDisconnect();
        },
        heartbeatIncoming: const Duration(seconds: 15),
        heartbeatOutgoing: const Duration(seconds: 15),
      ),
    );
    _client = client;
    client.activate();
  }

  void _onConnect(StompFrame frame) {
    _connected = true;
    _retryCount = 0;
    _reconnectTimer?.cancel();

    // Subscribe to user-specific room update queue.
    // Spring's /user/ prefix resolves to the authenticated user's queue.
    _client!.subscribe(
      destination: '/user/queue/room-updates',
      callback: (f) {
        if (f.body == null) return;
        try {
          final data = jsonDecode(f.body!) as Map<String, dynamic>;
          final type = data['type']?.toString() ?? '';
          final roomId = data['roomId']?.toString();
          final mentioned = (data['mentionedUsernames'] as List?)
              ?.map((e) => e.toString()).toList() ?? const <String>[];
          final content = data['content']?.toString() ?? '';
          final senderUsername = data['senderUsername']?.toString() ?? '';
          if (roomId != null && _onRoomUpdate != null) {
            _onRoomUpdate!(
              roomId,
              type,
              mentionedUsernames: mentioned,
              content: content,
              senderUsername: senderUsername,
            );
          }
        } catch (e) {
          debugPrint('[AppSTOMP] Parse error: $e');
        }
      },
    );
  }

  void _onDisconnect() {
    _connected = false;
    if (!_manualDisconnect) _scheduleReconnect();
  }

  void _scheduleReconnect() {
    // onWebSocketError and onWebSocketDone both fire for one drop.
    if (_reconnectTimer?.isActive ?? false) return;
    if (_retryCount >= _maxRetries) return;
    final delay = Duration(seconds: (1 << _retryCount).clamp(2, 60));
    _retryCount++;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(delay, () {
      if (!_manualDisconnect && _token != null) {
        _doConnect(_token!);
      }
    });
  }

  void disconnect() {
    _manualDisconnect = true;
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _connected = false;
    _userId = null;
    _token = null;
    _onRoomUpdate = null;
  }

}
