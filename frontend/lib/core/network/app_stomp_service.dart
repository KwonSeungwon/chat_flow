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
  /// After the backoff budget is spent, keep retrying at this interval.
  static const Duration _periodicRetry = Duration(seconds: 30);

  String? _userId;
  String? _token;
  Future<String?> Function()? _tokenProvider;
  RoomUpdateCallback? _onRoomUpdate;

  /// Bumped by connect()/disconnect(): a reconnect that awaited the token
  /// across either must not create a client.
  int _session = 0;

  /// Retrying stopped for auth: no stored token, or the server refused
  /// CONNECT for [_rejectedToken]. [reconnectNow] with a new token or the
  /// next [connect] (login) resumes.
  bool _authStopped = false;
  String? _rejectedToken;

  bool get isConnected => _connected;

  void connect({
    required String userId,
    required String token,
    required RoomUpdateCallback onRoomUpdate,
    /// Current token, re-read before every reconnect attempt (rotation,
    /// expiry). Null result stops retrying. Omitted: keep [token].
    Future<String?> Function()? tokenProvider,
  }) {
    if (_connected && _userId == userId) {
      // Same user, live socket: keep it, but the latest caller's callback
      // and token win (the previous owner may be gone).
      _onRoomUpdate = onRoomUpdate;
      _token = token;
      _tokenProvider = tokenProvider;
      return;
    }
    disconnect();
    _session++;
    _userId = userId;
    _token = token;
    _tokenProvider = tokenProvider;
    _onRoomUpdate = onRoomUpdate;
    _manualDisconnect = false;
    _authStopped = false;
    _rejectedToken = null;
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
        onStompError: (f) {
          debugPrint('[AppSTOMP] Error: ${f.body}');
          if (isCurrent() && isUnauthorizedStompError(f)) {
            _stopForAuth(rejected: token);
          }
        },
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

  void _stopForAuth({String? rejected}) {
    _authStopped = true;
    if (rejected != null) _rejectedToken = rejected;
    _reconnectTimer?.cancel();
    _connected = false;
  }

  void _scheduleReconnect() {
    if (_authStopped) return;
    // onWebSocketError and onWebSocketDone both fire for one drop.
    if (_reconnectTimer?.isActive ?? false) return;
    final Duration delay;
    if (_retryCount >= _maxRetries) {
      delay = _periodicRetry;
    } else {
      delay = Duration(seconds: (1 << _retryCount).clamp(2, 60));
      _retryCount++;
    }
    _reconnectTimer?.cancel();
    final session = _session;
    _reconnectTimer = Timer(delay, () => _reconnect(session));
  }

  Future<void> _reconnect(int session) async {
    if (_manualDisconnect || _token == null) return;
    // Re-read the current token on every attempt (rotation, re-login).
    if (_tokenProvider != null) {
      final String? fresh;
      try {
        fresh = await _tokenProvider!();
      } catch (e) {
        // Storage read failed: not an auth answer, try again later.
        debugPrint('[AppSTOMP] token read failed: $e');
        if (!_manualDisconnect && session == _session) _scheduleReconnect();
        return;
      }
      if (_manualDisconnect || session != _session) return;
      // No token, or the one already refused: stop until resume/login.
      if (fresh == null || fresh == _rejectedToken) {
        _stopForAuth();
        return;
      }
      _token = fresh;
    }
    _authStopped = false;
    _doConnect(_token!);
  }

  /// Reconnects right away if the socket is down (app resumed); also the way
  /// out of an auth stop once a new token is stored. No-op while connected
  /// or after [disconnect].
  void reconnectNow() {
    if (_connected || _manualDisconnect || _token == null) return;
    _reconnectTimer?.cancel();
    _retryCount = 0;
    _reconnect(_session);
  }

  void disconnect() {
    _manualDisconnect = true;
    _session++;
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _connected = false;
    _userId = null;
    _token = null;
    _onRoomUpdate = null;
  }

}
