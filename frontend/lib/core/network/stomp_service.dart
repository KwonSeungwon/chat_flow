import 'dart:async';
import 'dart:convert';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

typedef MessageCallback = void Function(Map<String, dynamic> message);
typedef ConnectionCallback = void Function(bool connected);

class StompService {
  StompClient? _client;
  bool _connected = false;
  bool _manualDisconnect = false;
  int _retryCount = 0;
  static const int _maxRetries = 10;
  Timer? _reconnectTimer;

  String? _currentRoomId;
  String? _currentUsername;
  String? _currentToken;
  MessageCallback? _onMessage;
  ConnectionCallback? _onConnectionChanged;

  bool get isConnected => _connected;

  void connect({
    required String roomId,
    required String username,
    required String token,
    required MessageCallback onMessage,
    required ConnectionCallback onConnectionChanged,
  }) {
    _currentRoomId = roomId;
    _currentUsername = username;
    _currentToken = token;
    _onMessage = onMessage;
    _onConnectionChanged = onConnectionChanged;
    _manualDisconnect = false;
    _retryCount = 0;
    _doConnect(token);
  }

  void _doConnect(String token) {
    final wsUrl =
        dotenv.env['WS_URL'] ?? 'ws://43.201.22.86:8000/ws-native/websocket';

    _client = StompClient(
      config: StompConfig(
        url: wsUrl,
        onConnect: (frame) => _onConnect(frame),
        onDisconnect: (frame) => _onDisconnect(),
        onStompError: (frame) => _onError(),
        onWebSocketError: (error) => _onError(),
        heartbeatIncoming: const Duration(seconds: 10),
        heartbeatOutgoing: const Duration(seconds: 10),
        webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
      ),
    );
    _client!.activate();
  }

  void _onConnect(StompFrame frame) {
    _connected = true;
    _retryCount = 0;
    _onConnectionChanged?.call(true);

    // Subscribe to room messages
    _client!.subscribe(
      destination: '/topic/chat/$_currentRoomId',
      callback: (frame) {
        if (frame.body != null) {
          try {
            final msg = jsonDecode(frame.body!) as Map<String, dynamic>;
            _onMessage?.call(msg);
          } catch (_) {}
        }
      },
    );

    // Subscribe to errors
    _client!.subscribe(
      destination: '/topic/chat/$_currentRoomId/errors',
      callback: (frame) {},
    );

    // Send JOIN via /app/chat.addUser
    _client!.send(
      destination: '/app/chat.addUser',
      body: jsonEncode({
        'chatRoomId': _currentRoomId,
        'username': _currentUsername,
        'content': '',
        'type': 'JOIN',
        'timestamp': DateTime.now().toIso8601String(),
      }),
    );
  }

  void _onDisconnect() {
    _connected = false;
    _onConnectionChanged?.call(false);
    if (!_manualDisconnect) _scheduleReconnect();
  }

  void _onError() {
    _connected = false;
    _onConnectionChanged?.call(false);
    if (!_manualDisconnect) _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_retryCount >= _maxRetries) return;
    final delay = Duration(seconds: (1 << _retryCount).clamp(1, 30));
    _retryCount++;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(delay, () {
      if (!_manualDisconnect && _currentToken != null) {
        _doConnect(_currentToken!);
      }
    });
  }

  void sendMessage(Map<String, dynamic> message) {
    if (_connected && _client != null) {
      _client!.send(
        destination: '/app/chat.sendMessage',
        body: jsonEncode(message),
      );
    }
  }

  void sendJoin(Map<String, dynamic> message) {
    if (_connected && _client != null) {
      _client!.send(
        destination: '/app/chat.addUser',
        body: jsonEncode(message),
      );
    }
  }

  void disconnect() {
    _manualDisconnect = true;
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _connected = false;
    _currentRoomId = null;
  }

  void dispose() {
    disconnect();
  }
}
