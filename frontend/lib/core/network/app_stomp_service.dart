import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart' show debugPrint, kIsWeb;
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

typedef RoomUpdateCallback = void Function(String roomId, String type);

/// App-level single STOMP connection for global subscriptions
/// (e.g. /user/queue/room-updates for unread increments).
/// Separate from per-room StompService to avoid lifecycle coupling.
class AppStompService {
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
    if (_connected && _userId == userId) return;
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

    final url = kIsWeb
        ? _webWsUrl()
        : (dotenv.env['WS_URL'] ?? 'wss://app.chatflow.ai.kr/ws-native');

    _client = StompClient(
      config: StompConfig(
        url: url,
        webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
        onConnect: _onConnect,
        onDisconnect: (_) => _onDisconnect(),
        onStompError: (f) => debugPrint('[AppSTOMP] Error: ${f.body}'),
        onWebSocketError: (e) => debugPrint('[AppSTOMP] WS error: $e'),
        heartbeatIncoming: const Duration(seconds: 15),
        heartbeatOutgoing: const Duration(seconds: 15),
      ),
    );
    _client!.activate();
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
          if (roomId != null && _onRoomUpdate != null) {
            _onRoomUpdate!(roomId, type);
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

  static String _webWsUrl() {
    final uri = Uri.base;
    final scheme = uri.scheme == 'https' ? 'wss' : 'ws';
    final port = (uri.hasPort && uri.port != 80 && uri.port != 443)
        ? ':${uri.port}'
        : '';
    return '$scheme://${uri.host}$port/ws-native';
  }
}
