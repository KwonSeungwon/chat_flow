import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';
import '../../core/network/stomp_service.dart';
import '../../core/services/fcm_service.dart';
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
      state = AsyncValue.data(rooms);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  /// Creates a room and returns its ID for navigation.
  Future<String?> createRoom({
    required String name,
    String? description,
    String? color,
  }) async {
    final resp = await _dioClient.dio.post('/api/chat/rooms', data: {
      'name': name,
      if (description != null) 'description': description,
      if (color != null) 'color': color,
    });
    // Extract room ID first — fetchRooms failure must not mask successful creation
    final data = resp.data;
    final roomId = data is Map
        ? (data['data']?['id'] ?? data['id'])?.toString()
        : null;
    try {
      await fetchRooms();
    } catch (_) {
      // Best-effort refresh — room was created regardless
    }
    return roomId;
  }
}

// ---------------------------------------------------------------------------
// Chat Messages
// ---------------------------------------------------------------------------

class ChatMessagesState {
  final List<ChatMessage> messages;
  final bool isConnected;
  final bool isLoadingHistory;
  final bool isAiLoading;
  final bool isSummaryLoading;

  const ChatMessagesState({
    this.messages = const [],
    this.isConnected = false,
    this.isLoadingHistory = false,
    this.isAiLoading = false,
    this.isSummaryLoading = false,
  });

  ChatMessagesState copyWith({
    List<ChatMessage>? messages,
    bool? isConnected,
    bool? isLoadingHistory,
    bool? isAiLoading,
    bool? isSummaryLoading,
  }) {
    return ChatMessagesState(
      messages: messages ?? this.messages,
      isConnected: isConnected ?? this.isConnected,
      isLoadingHistory: isLoadingHistory ?? this.isLoadingHistory,
      isAiLoading: isAiLoading ?? this.isAiLoading,
      isSummaryLoading: isSummaryLoading ?? this.isSummaryLoading,
    );
  }
}

class ChatNotifier extends StateNotifier<ChatMessagesState> {
  final DioClient _dioClient;
  final StompService _stompService = StompService();
  final String _token;
  final String _username;
  final String _userId;
  static const _storage = FlutterSecureStorage();

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

    // Load AI summaries and merge into history
    await _fetchSummaries(roomId);

    // Connect WebSocket
    _stompService.connect(
      roomId: roomId,
      username: _username,
      userId: _userId,
      token: _token,
      tokenProvider: () async =>
          await _storage.read(key: 'chatflow-token') ?? _token,
      onMessage: (msg) => _onMessage(msg),
      onConnectionChanged: (connected) {
        if (mounted) {
          state = state.copyWith(isConnected: connected);
        }
      },
    );

    // Subscribe FCM token to room topic for push notifications (fire & forget)
    _subscribeFcmToRoom(roomId);
  }

  Future<void> _subscribeFcmToRoom(String roomId) async {
    try {
      final token = await FcmService.getToken();
      if (token == null) return;
      await _dioClient.dio.post('/api/fcm/subscribe', data: {
        'token': token,
        'roomId': roomId,
      });
    } catch (_) {
      // Best-effort — FCM failure must not interrupt room join
    }
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

  Future<void> _fetchSummaries(String roomId) async {
    try {
      final resp = await _dioClient.dio.get('/api/ai-summary/room/$roomId');
      final data = resp.data;
      List<dynamic> items;
      if (data is List) {
        items = data;
      } else if (data is Map && data['data'] is List) {
        items = data['data'] as List;
      } else {
        return;
      }
      final summaries = items
          .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
          .toList();
      if (summaries.isEmpty || !mounted) return;
      final existing = state.messages;
      final merged = [...existing, ...summaries];
      final seen = <String>{};
      final deduped =
          merged.where((m) => seen.add(m.effectiveId)).toList();
      deduped.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      state = state.copyWith(messages: deduped);
    } catch (_) {
      // Best-effort — summary load must not interrupt chat
    }
  }

  Future<String> requestSummary(String roomId) async {
    state = state.copyWith(isSummaryLoading: true);
    try {
      final resp = await _dioClient.dio
          .post('/api/ai-summary/request', data: {'chatRoomId': roomId});
      final data = resp.data;
      if (data is Map && data['success'] == false) {
        return data['message']?.toString() ?? '요약할 메시지가 충분하지 않습니다.';
      }
      return ''; // success
    } finally {
      if (mounted) state = state.copyWith(isSummaryLoading: false);
    }
  }

  Future<void> askAi(String roomId, String question) async {
    final questionMsg = ChatMessage(
      messageId: 'ai-q-${DateTime.now().microsecondsSinceEpoch}',
      chatRoomId: roomId,
      userId: _userId,
      username: _username,
      content: question,
      type: 'CHAT',
      timestamp: DateTime.now().toIso8601String(),
    );
    state = state.copyWith(messages: [...state.messages, questionMsg], isAiLoading: true);

    try {
      final resp = await _dioClient.dio.post('/api/ai-summary/ask', data: {
        'chatRoomId': roomId,
        'question': question,
      });
      if (!mounted) return;
      // Parse AI response directly from REST — no WebSocket dependency
      final data = resp.data;
      Map<String, dynamic>? msgJson;
      if (data is Map && data['data'] is Map) {
        msgJson = data['data'] as Map<String, dynamic>;
      } else if (data is Map && data['messageId'] != null) {
        msgJson = data as Map<String, dynamic>;
      }
      if (msgJson != null) {
        final aiMsg = ChatMessage.fromJson(msgJson);
        if (!state.messages.any((m) => m.effectiveId == aiMsg.effectiveId)) {
          state = state.copyWith(messages: [...state.messages, aiMsg], isAiLoading: false);
        } else {
          state = state.copyWith(isAiLoading: false);
        }
      } else {
        state = state.copyWith(isAiLoading: false);
      }
    } catch (e) {
      if (!mounted) return;
      final updated = state.messages
          .where((m) => m.effectiveId != questionMsg.effectiveId)
          .toList();
      state = state.copyWith(messages: updated, isAiLoading: false);
      rethrow;
    }
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
