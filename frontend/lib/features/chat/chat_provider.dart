import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../core/network/stomp_service.dart';
import '../../shared/models/chat_message.dart';
import '../../shared/models/chat_room.dart';
import '../auth/auth_provider.dart';

// ---------------------------------------------------------------------------
// Chat Rooms
// ---------------------------------------------------------------------------

final chatRoomsProvider =
    StateNotifierProvider<ChatRoomsNotifier, AsyncValue<List<ChatRoom>>>((ref) {
  return ChatRoomsNotifier(ref.watch(dioClientProvider));
});

class ChatRoomsNotifier extends StateNotifier<AsyncValue<List<ChatRoom>>> {
  final DioClient _dioClient;
  static const _defaultRoomIds = ['general', 'tech', 'random'];

  ChatRoomsNotifier(this._dioClient) : super(const AsyncValue.loading()) {
    fetchRooms();
  }

  Future<void> fetchRooms() async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms');
      final data = resp.data;
      List<dynamic> list;
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'] as List;
      } else if (data is Map && data['content'] is List) {
        list = data['content'] as List;
      } else {
        list = [];
      }
      final rooms =
          list
              .map((e) => ChatRoom.fromJson(e as Map<String, dynamic>))
              .toList();
      if (rooms.isEmpty) {
        state = AsyncValue.data(
          _defaultRoomIds
              .map((id) => ChatRoom(id: id, name: id, participantCount: 0))
              .toList(),
        );
      } else {
        state = AsyncValue.data(rooms);
      }
    } catch (_) {
      state = AsyncValue.data(
        _defaultRoomIds
            .map((id) => ChatRoom(id: id, name: id, participantCount: 0))
            .toList(),
      );
    }
  }

  Future<void> createRoom({
    required String name,
    String? description,
    String? color,
  }) async {
    try {
      await _dioClient.dio.post('/api/chat/rooms', data: {
        'name': name,
        if (description != null) 'description': description,
        if (color != null) 'color': color,
      });
      await fetchRooms();
    } catch (_) {
      rethrow;
    }
  }
}

// ---------------------------------------------------------------------------
// Chat Messages
// ---------------------------------------------------------------------------

class ChatMessagesState {
  final List<ChatMessage> messages;
  final bool isConnected;
  final bool isLoadingHistory;

  const ChatMessagesState({
    this.messages = const [],
    this.isConnected = false,
    this.isLoadingHistory = false,
  });

  ChatMessagesState copyWith({
    List<ChatMessage>? messages,
    bool? isConnected,
    bool? isLoadingHistory,
  }) {
    return ChatMessagesState(
      messages: messages ?? this.messages,
      isConnected: isConnected ?? this.isConnected,
      isLoadingHistory: isLoadingHistory ?? this.isLoadingHistory,
    );
  }
}

class ChatNotifier extends StateNotifier<ChatMessagesState> {
  final DioClient _dioClient;
  final StompService _stompService = StompService();
  final String _token;
  final String _username;
  final String _userId;

  ChatNotifier(
    this._dioClient, {
    required String token,
    required String username,
    required String userId,
  })  : _token = token,
        _username = username,
        _userId = userId,
        super(const ChatMessagesState());

  Future<void> joinRoom(String roomId) async {
    _stompService.disconnect();
    state = state.copyWith(
      messages: [],
      isConnected: false,
      isLoadingHistory: true,
    );

    // Load history
    try {
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages',
        queryParameters: {'size': 50},
      );
      final data = resp.data;
      List<dynamic> items;
      if (data is Map &&
          data['data'] is Map &&
          data['data']['content'] is List) {
        items = data['data']['content'] as List;
      } else if (data is Map && data['content'] is List) {
        items = data['content'] as List;
      } else if (data is List) {
        items = data;
      } else {
        items = [];
      }
      final history =
          items
              .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
              .toList()
              .reversed
              .toList();
      state = state.copyWith(messages: history, isLoadingHistory: false);
    } catch (_) {
      state = state.copyWith(isLoadingHistory: false);
    }

    // Connect WebSocket
    _stompService.connect(
      roomId: roomId,
      username: _username,
      token: _token,
      onMessage: (msg) => _onMessage(msg),
      onConnectionChanged: (connected) {
        if (mounted) {
          state = state.copyWith(isConnected: connected);
        }
      },
    );
  }

  void _onMessage(Map<String, dynamic> rawMsg) {
    if (!mounted) return;
    final msg = ChatMessage.fromJson(rawMsg);
    final existing = state.messages;
    // Dedup by effectiveId
    if (existing.any((m) => m.effectiveId == msg.effectiveId)) return;
    final updated = [...existing, msg];
    // Cap at 500
    final capped =
        updated.length > 500 ? updated.sublist(updated.length - 500) : updated;
    state = state.copyWith(messages: capped);
  }

  void sendMessage({required String roomId, required String content}) {
    _stompService.sendMessage({
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': content,
      'type': 'CHAT',
      'timestamp': DateTime.now().toIso8601String(),
    });
  }

  void disconnect() => _stompService.disconnect();

  @override
  void dispose() {
    _stompService.dispose();
    super.dispose();
  }
}

final chatNotifierProvider =
    StateNotifierProvider.family<ChatNotifier, ChatMessagesState, String>(
  (ref, roomId) {
    final auth = ref.watch(authProvider);
    final notifier = ChatNotifier(
      ref.watch(dioClientProvider),
      token: auth.token ?? '',
      username: auth.username,
      userId: auth.userId ?? '',
    );
    // Guard: only join when token is available (prevents empty-token WebSocket on auth hydration)
    if (auth.token != null) {
      notifier.joinRoom(roomId);
    }
    ref.onDispose(() => notifier.disconnect());
    return notifier;
  },
);
