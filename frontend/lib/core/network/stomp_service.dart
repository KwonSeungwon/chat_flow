import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart' show debugPrint, kIsWeb;
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

typedef MessageCallback = void Function(Map<String, dynamic> message);
typedef ConnectionCallback = void Function(bool connected);
typedef ReadReceiptCallback = void Function(String messageId, int readCount);
typedef TypingCallback = void Function(String username);

class StompService {
  StompClient? _client;
  bool _connected = false;
  bool _manualDisconnect = false;
  int _retryCount = 0;
  static const int _maxRetries = 10;
  Timer? _reconnectTimer;

  String? _currentRoomId;
  String? _currentUsername;
  String? _currentUserId;
  String? _currentToken;
  Future<String?> Function()? _tokenProvider;
  MessageCallback? _onMessage;
  ConnectionCallback? _onConnectionChanged;
  ReadReceiptCallback? _onReadReceipt;
  TypingCallback? _onTyping;
  void Function(String? redirectTo, String? roomName)? _onRoomFull;

  bool get isConnected => _connected;

  void connect({
    required String roomId,
    required String username,
    required String userId,
    required String token,
    required MessageCallback onMessage,
    required ConnectionCallback onConnectionChanged,
    Future<String?> Function()? tokenProvider,
    ReadReceiptCallback? onReadReceipt,
    TypingCallback? onTyping,
    void Function(String? redirectTo, String? roomName)? onRoomFull,
  }) {
    _currentRoomId = roomId;
    _currentUsername = username;
    _currentUserId = userId;
    _currentToken = token;
    _tokenProvider = tokenProvider;
    _onMessage = onMessage;
    _onConnectionChanged = onConnectionChanged;
    _onReadReceipt = onReadReceipt;
    _onTyping = onTyping;
    _onRoomFull = onRoomFull;
    _manualDisconnect = false;
    _retryCount = 0;
    _doConnect(token);
  }

  void _doConnect(String token) {
    // Deactivate existing client before creating a new one to prevent ghost connections
    _client?.deactivate();

    final wsUrl = kIsWeb
        ? _webWsUrl()
        : (dotenv.env['WS_URL'] ?? 'wss://app.chatflow.ai.kr/ws-native');

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
          } catch (e) {
            debugPrint('[STOMP] JSON parse error: $e');
          }
        }
      },
    );

    // Subscribe to server-side errors
    _client!.subscribe(
      destination: '/topic/chat/$_currentRoomId/errors',
      callback: (frame) {
        if (frame.body == null) return;
        try {
          final data = jsonDecode(frame.body!) as Map<String, dynamic>;
          final type = data['type']?.toString();
          if (type == 'ROOM_FULL') {
            _onRoomFull?.call(data['redirectTo']?.toString(), data['roomName']?.toString());
          } else {
            debugPrint('[STOMP] Server error: $data');
          }
        } catch (_) {}
      },
    );

    // Subscribe to read receipts
    _client!.subscribe(
      destination: '/topic/chat/$_currentRoomId/read-receipts',
      callback: (frame) {
        if (frame.body != null && _onReadReceipt != null) {
          try {
            final data = jsonDecode(frame.body!) as Map<String, dynamic>;
            final messageId = data['messageId']?.toString();
            final readCount = (data['readCount'] as num?)?.toInt() ?? 0;
            if (messageId != null) {
              _onReadReceipt!(messageId, readCount);
            }
          } catch (_) {}
        }
      },
    );

    // Subscribe to typing indicators
    _client!.subscribe(
      destination: '/topic/chat/$_currentRoomId/typing',
      callback: (frame) {
        if (frame.body != null && _onTyping != null) {
          try {
            final data = jsonDecode(frame.body!) as Map<String, dynamic>;
            final username = data['username']?.toString();
            if (username != null && username != _currentUsername) {
              _onTyping!(username);
            }
          } catch (_) {}
        }
      },
    );

    // Send JOIN via /app/chat.addUser
    _client!.send(
      destination: '/app/chat.addUser',
      body: jsonEncode({
        'chatRoomId': _currentRoomId,
        'userId': _currentUserId ?? '',
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
    _reconnectTimer = Timer(delay, () async {
      if (!_manualDisconnect && _currentToken != null) {
        // Refresh token from secure storage before reconnecting
        if (_tokenProvider != null) {
          final freshToken = await _tokenProvider!();
          if (freshToken != null) _currentToken = freshToken;
        }
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

  void sendTyping(String roomId) {
    if (_connected && _client != null) {
      _client!.send(
        destination: '/app/chat.typing',
        body: jsonEncode({
          'chatRoomId': roomId,
          'username': _currentUsername ?? '',
        }),
      );
    }
  }

  void sendReadReceipt(String roomId, String messageId) {
    if (_connected && _client != null) {
      _client!.send(
        destination: '/app/chat.readReceipt',
        body: jsonEncode({
          'chatRoomId': roomId,
          'messageId': messageId,
          'userId': _currentUserId ?? '',
          'username': _currentUsername ?? '',
        }),
      );
    }
  }

  void disconnect() {
    _manualDisconnect = true;
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _connected = false;
    _currentRoomId = null;
    _currentToken = null;
    _tokenProvider = null;
    _currentUsername = null;
    _currentUserId = null;
    _onMessage = null;
    _onConnectionChanged = null;
    _onReadReceipt = null;
    _onTyping = null;
    _onRoomFull = null;
  }

  void dispose() {
    disconnect();
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
