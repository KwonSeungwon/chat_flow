import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';
import '../../core/network/stomp_service.dart';
import '../../core/services/fcm_service.dart';
import '../../shared/models/chat_message.dart';
import '../../shared/models/chat_room.dart';
import '../../shared/models/patient_card.dart';
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

  Future<bool> deleteRoom(String id) async {
    try {
      await _dioClient.dio.delete('/api/chat/rooms/$id');
      await fetchRooms();
      return true;
    } on DioException {
      return false;
    }
  }

  Future<Map<String, int>> fetchUnreadCounts() async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms/unread-counts');
      final data = resp.data;
      if (data is Map && data['data'] is Map) {
        final raw = data['data'] as Map;
        return raw.map((k, v) => MapEntry(k.toString(), (v as num).toInt()));
      }
    } catch (_) {}
    return {};
  }

  /// Creates a room and returns its ID for navigation.
  Future<String?> createRoom({
    required String name,
    String? description,
    String? color,
    String roomType = 'GENERAL',
    bool isPrivate = false,
    String? password,
    String? allowedRoles,
  }) async {
    try {
      final resp = await _dioClient.dio.post('/api/chat/rooms', data: {
        'name': name,
        if (description != null) 'description': description,
        if (color != null) 'color': color,
        'roomType': roomType,
        'isPrivate': isPrivate,
        if (password != null) 'password': password,
        if (allowedRoles != null) 'allowedRoles': allowedRoles,
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
    } on DioException {
      return null;
    }
  }
}

// ---------------------------------------------------------------------------
// Global unread counts (per room) — updated by ChatNotifier, read by sidebar
// ---------------------------------------------------------------------------

final roomUnreadCountsProvider = StateProvider<Map<String, int>>((ref) => {});

// ---------------------------------------------------------------------------
// Muted Rooms (local-only, persisted to FlutterSecureStorage)
// ---------------------------------------------------------------------------

const _mutedKey = 'chatflow-muted-rooms';
const _muteStorage = FlutterSecureStorage();

final mutedRoomsProvider = StateNotifierProvider<MutedRoomsNotifier, Set<String>>((ref) {
  return MutedRoomsNotifier();
});

class MutedRoomsNotifier extends StateNotifier<Set<String>> {
  MutedRoomsNotifier() : super({}) { _load(); }

  Future<void> _load() async {
    final raw = await _muteStorage.read(key: _mutedKey);
    if (raw != null && raw.isNotEmpty) {
      state = Set<String>.from(jsonDecode(raw) as List);
    }
  }

  Future<void> toggle(String roomId) async {
    final updated = Set<String>.from(state);
    if (updated.contains(roomId)) {
      updated.remove(roomId);
    } else {
      updated.add(roomId);
    }
    state = updated;
    await _muteStorage.write(key: _mutedKey, value: jsonEncode(updated.toList()));
  }

  bool isMuted(String roomId) => state.contains(roomId);
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
  /// messageId → read count
  final Map<String, int> readCounts;
  /// Last message the current user has read (fetched from backend on join)
  final String? lastReadMessageId;
  final ChatMessage? replyTarget;
  final bool roomDeleted;
  final Set<String> typingUsers;

  const ChatMessagesState({
    this.messages = const [],
    this.isConnected = false,
    this.isLoadingHistory = false,
    this.isAiLoading = false,
    this.isSummaryLoading = false,
    this.readCounts = const {},
    this.lastReadMessageId,
    this.replyTarget,
    this.roomDeleted = false,
    this.typingUsers = const {},
  });

  ChatMessagesState copyWith({
    List<ChatMessage>? messages,
    bool? isConnected,
    bool? isLoadingHistory,
    bool? isAiLoading,
    bool? isSummaryLoading,
    Map<String, int>? readCounts,
    String? lastReadMessageId,
    bool clearLastReadMessageId = false,
    ChatMessage? replyTarget,
    bool clearReplyTarget = false,
    bool? roomDeleted,
    Set<String>? typingUsers,
  }) {
    return ChatMessagesState(
      messages: messages ?? this.messages,
      isConnected: isConnected ?? this.isConnected,
      isLoadingHistory: isLoadingHistory ?? this.isLoadingHistory,
      isAiLoading: isAiLoading ?? this.isAiLoading,
      isSummaryLoading: isSummaryLoading ?? this.isSummaryLoading,
      readCounts: readCounts ?? this.readCounts,
      lastReadMessageId: clearLastReadMessageId ? null : (lastReadMessageId ?? this.lastReadMessageId),
      replyTarget: clearReplyTarget ? null : (replyTarget ?? this.replyTarget),
      roomDeleted: roomDeleted ?? this.roomDeleted,
      typingUsers: typingUsers ?? this.typingUsers,
    );
  }
}

class ChatNotifier extends StateNotifier<ChatMessagesState> {
  final DioClient _dioClient;
  final Ref _ref;
  final StompService _stompService = StompService();
  final String _token;
  final String _username;
  final String _userId;
  static const _storage = FlutterSecureStorage();
  Timer? _typingDebounce;
  final Map<String, Timer> _typingTimers = {};
  final List<Map<String, dynamic>> _offlineQueue = [];

  ChatNotifier(
    this._dioClient, {
    required Ref ref,
    required String token,
    required String username,
    required String userId,
  })  : _ref = ref,
        _token = token,
        _username = username,
        _userId = userId,
        super(const ChatMessagesState());

  Future<void> joinRoom(String roomId) async {
    _stompService.disconnect();
    state = state.copyWith(
      messages: [],
      isConnected: false,
      isLoadingHistory: true,
      clearLastReadMessageId: true,
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
      // Merge history with any live STOMP messages already received
      final live = state.messages;
      final merged = [...history, ...live];
      final seen = <String>{};
      final deduped = merged.where((m) => seen.add(m.effectiveId)).toList();
      deduped.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      state = state.copyWith(messages: deduped, isLoadingHistory: false);
    } catch (_) {
      state = state.copyWith(isLoadingHistory: false);
    }

    // Load AI summaries and merge into history
    await _fetchSummaries(roomId);

    // Fetch last-read position and compute unread count for this session
    await _fetchLastReadAndUpdateUnread(roomId);

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
          if (connected) _flushOfflineQueue();
        }
      },
      onReadReceipt: (messageId, readCount) {
        if (!mounted) return;
        final updated = Map<String, int>.from(state.readCounts);
        updated[messageId] = readCount;
        state = state.copyWith(readCounts: updated);
      },
      onTyping: (username) {
        if (!mounted) return;
        _onTypingReceived(username);
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
    final type = rawMsg['type']?.toString().toUpperCase();

    if (type == 'ROOM_DELETED') {
      state = state.copyWith(roomDeleted: true);
      return;
    }

    // Handle soft-deleted message broadcast
    if (type == 'MESSAGE_DELETED') {
      final deletedId = rawMsg['messageId']?.toString();
      if (deletedId == null) return;
      final updated = state.messages.map((m) {
        if (m.effectiveId == deletedId || m.messageId == deletedId) {
          return ChatMessage(
            id: m.id, messageId: m.messageId,
            chatRoomId: m.chatRoomId, userId: m.userId, username: m.username,
            content: '삭제된 메시지입니다.', timestamp: m.timestamp, type: m.type,
            priority: m.priority, isAiGenerated: m.isAiGenerated,
            deleted: true,
          );
        }
        return m;
      }).toList();
      state = state.copyWith(messages: updated);
      return;
    }

    if (type == 'MESSAGE_EDITED') {
      final editedId = rawMsg['messageId']?.toString();
      final newContent = rawMsg['content']?.toString();
      final editedAt = rawMsg['editedAt']?.toString();
      if (editedId == null || newContent == null) return;
      final updated = state.messages.map((m) {
        if (m.effectiveId == editedId || m.messageId == editedId) {
          return ChatMessage(
            id: m.id, messageId: m.messageId,
            chatRoomId: m.chatRoomId, userId: m.userId, username: m.username,
            content: newContent, timestamp: m.timestamp, type: m.type,
            priority: m.priority, isAiGenerated: m.isAiGenerated,
            deleted: m.deleted, edited: true, editedAt: editedAt,
          );
        }
        return m;
      }).toList();
      state = state.copyWith(messages: updated);
      return;
    }

    if (type == 'REACTION_UPDATED') {
      final msgId = rawMsg['messageId']?.toString();
      if (msgId == null) return;
      final reactions = ChatMessage.parseReactions(rawMsg['reactions']);
      final updated = state.messages.map((m) {
        if (m.effectiveId == msgId || m.messageId == msgId) {
          return ChatMessage(
            id: m.id, messageId: m.messageId, chatRoomId: m.chatRoomId,
            userId: m.userId, username: m.username, content: m.content,
            timestamp: m.timestamp, type: m.type, priority: m.priority,
            isAiGenerated: m.isAiGenerated, deleted: m.deleted,
            edited: m.edited, editedAt: m.editedAt, pinned: m.pinned,
            reactions: reactions,
            fileUrl: m.fileUrl, fileName: m.fileName, fileContentType: m.fileContentType,
            parentMessageId: m.parentMessageId, parentMessagePreview: m.parentMessagePreview,
          );
        }
        return m;
      }).toList();
      state = state.copyWith(messages: updated);
      return;
    }

    if (type == 'MESSAGE_PINNED' || type == 'MESSAGE_UNPINNED') {
      // Refresh room list to update pinnedMessageId
      _ref.read(chatRoomsProvider.notifier).fetchRooms();
      return;
    }

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

  Future<bool> deleteMessage(String roomId, String messageId) async {
    final originalMessages = List<ChatMessage>.from(state.messages);
    // Optimistic update
    final updated = state.messages.map((m) {
      if (m.effectiveId == messageId || m.messageId == messageId) {
        return ChatMessage(
          id: m.id, messageId: m.messageId,
          chatRoomId: m.chatRoomId, userId: m.userId, username: m.username,
          content: '삭제된 메시지입니다.', timestamp: m.timestamp, type: m.type,
          priority: m.priority, isAiGenerated: m.isAiGenerated,
          deleted: true,
        );
      }
      return m;
    }).toList();
    state = state.copyWith(messages: updated);

    try {
      await _dioClient.dio.delete('/api/chat/rooms/$roomId/messages/$messageId');
      return true;
    } catch (_) {
      // Rollback on failure — restore original message list
      state = state.copyWith(messages: originalMessages);
      return false;
    }
  }

  Future<bool> editMessage(String roomId, String messageId, String newContent) async {
    final originalMessages = List<ChatMessage>.from(state.messages);
    // Optimistic update
    final updated = state.messages.map((m) {
      if (m.effectiveId == messageId || m.messageId == messageId) {
        return ChatMessage(
          id: m.id, messageId: m.messageId,
          chatRoomId: m.chatRoomId, userId: m.userId, username: m.username,
          content: newContent, timestamp: m.timestamp, type: m.type,
          priority: m.priority, isAiGenerated: m.isAiGenerated,
          deleted: m.deleted, edited: true, editedAt: DateTime.now().toIso8601String(),
        );
      }
      return m;
    }).toList();
    state = state.copyWith(messages: updated);

    try {
      await _dioClient.dio.put(
        '/api/chat/rooms/$roomId/messages/$messageId',
        data: {'content': newContent},
      );
      return true;
    } catch (_) {
      state = state.copyWith(messages: originalMessages);
      return false;
    }
  }

  Future<bool> leaveRoom(String roomId) async {
    try {
      await _dioClient.dio.delete('/api/chat/rooms/$roomId/members/me');
      _stompService.disconnect();
      state = const ChatMessagesState();
      return true;
    } catch (_) {
      return false;
    }
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

  Future<void> _fetchLastReadAndUpdateUnread(String roomId) async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms/$roomId/last-read');
      final data = resp.data;
      final lastReadId = data is Map
          ? ((data['data'] as Map?)?['lastReadMessageId']?.toString() ?? '')
          : '';
      if (!mounted) return;

      // Find how many CHAT messages are after the lastRead position (exclude JOIN/LEAVE/AI_SUMMARY)
      int unreadCount = 0;
      if (lastReadId.isNotEmpty) {
        final chatMsgs = state.messages.where((m) => m.type == 'CHAT').toList();
        final idx = chatMsgs.indexWhere((m) => m.effectiveId == lastReadId);
        if (idx >= 0 && idx < chatMsgs.length - 1) {
          unreadCount = chatMsgs.length - idx - 1;
        }
        state = state.copyWith(lastReadMessageId: lastReadId);
      }

      // Update global unread counts map
      final current = Map<String, int>.from(_ref.read(roomUnreadCountsProvider));
      current[roomId] = unreadCount;
      _ref.read(roomUnreadCountsProvider.notifier).state = current;
    } catch (_) {
      // Non-critical — best effort
    }
  }

  /// Called when user enters a room. Clears local unread count and persists last-read position.
  void markRoomRead(String roomId) {
    final current = Map<String, int>.from(_ref.read(roomUnreadCountsProvider));
    current[roomId] = 0;
    _ref.read(roomUnreadCountsProvider.notifier).state = current;

    // Persist last-read position so it survives app restarts (best-effort)
    final chatMsgs = state.messages.where((m) => m.type == 'CHAT').toList();
    if (chatMsgs.isNotEmpty) {
      _persistLastRead(roomId, chatMsgs.last.effectiveId);
    }
  }

  Future<void> _persistLastRead(String roomId, String lastReadMessageId) async {
    try {
      await _dioClient.dio.put(
        '/api/chat/rooms/$roomId/last-read',
        data: {'lastReadMessageId': lastReadMessageId},
      );
    } catch (_) {
      // Best-effort — failure must not interrupt room viewing
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

  static const _aiQuestionPrefix = '[AI에게] ';

  Future<void> askAi(String roomId, String question) async {
    // Send AI question via STOMP for persistence
    final taggedContent = '$_aiQuestionPrefix$question';
    _stompService.sendMessage({
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': taggedContent,
      'type': 'CHAT',
      'timestamp': DateTime.now().toIso8601String(),
    });
    state = state.copyWith(isAiLoading: true);

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
      state = state.copyWith(isAiLoading: false);
      // Extract backend error message for display
      String errMsg = 'AI 답변을 가져오는데 실패했습니다.';
      if (e is DioException && e.response?.data is Map) {
        errMsg = (e.response!.data as Map)['message']?.toString() ?? errMsg;
      }
      throw Exception(errMsg);
    }
  }

  void setReplyTarget(ChatMessage message) {
    state = state.copyWith(replyTarget: message);
  }

  void clearReplyTarget() {
    state = state.copyWith(clearReplyTarget: true);
  }

  void sendMessage({required String roomId, required String content, String priority = 'ROUTINE'}) {
    final reply = state.replyTarget;
    final msg = {
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': content,
      'type': 'CHAT',
      'priority': priority,
      'timestamp': DateTime.now().toIso8601String(),
      if (reply != null) 'parentMessageId': reply.effectiveId,
    };
    if (_stompService.isConnected) {
      _stompService.sendMessage(msg);
    } else {
      _offlineQueue.add(msg);
      // Show as pending local message
      final localMsg = ChatMessage.fromJson(Map<String, dynamic>.from(msg));
      state = state.copyWith(messages: [...state.messages, localMsg]);
    }
    if (reply != null) clearReplyTarget();
  }

  void _flushOfflineQueue() {
    if (_offlineQueue.isEmpty) return;
    final queued = List<Map<String, dynamic>>.from(_offlineQueue);
    _offlineQueue.clear();
    // Remove locally-added messages to prevent duplicates when server broadcasts them back
    final queuedIds = queued.map((m) =>
        '${m['timestamp']}-${m['username']}-${m['content'].hashCode}').toSet();
    final cleaned = state.messages.where((m) => !queuedIds.contains(m.effectiveId)).toList();
    state = state.copyWith(messages: cleaned);
    for (final msg in queued) {
      _stompService.sendMessage(msg);
    }
  }

  void sendPatientCard(String roomId, PatientCard card) {
    _stompService.sendMessage({
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': jsonEncode(card.toJson()),
      'type': 'PATIENT_CARD',
      'priority': 'ROUTINE',
      'timestamp': DateTime.now().toIso8601String(),
    });
  }

  Future<void> uploadAndSendFile({
    required String roomId,
    required String fileName,
    required Uint8List bytes,
    required String mimeType,
    String content = '',
  }) async {
    final result = await _dioClient.uploadFile(
      fileName: fileName,
      bytes: bytes,
      mimeType: mimeType,
    );
    final fileUrl = result['fileUrl']?.toString() ?? '';
    final storedName = result['fileName']?.toString() ?? fileName;
    final contentType = result['fileContentType']?.toString() ?? mimeType;

    final msgContent = content.isNotEmpty ? content : '[파일] $storedName';

    _stompService.sendMessage({
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': msgContent,
      'type': 'FILE',
      'fileUrl': fileUrl,
      'fileName': storedName,
      'fileContentType': contentType,
      'priority': 'ROUTINE',
      'timestamp': DateTime.now().toIso8601String(),
    });
  }

  Future<void> toggleReaction(String roomId, String messageId, String emoji) async {
    try {
      await _dioClient.dio.post('/api/chat/rooms/$roomId/messages/$messageId/reactions',
          data: {'emoji': emoji});
    } catch (_) {}
  }

  Future<bool> forwardMessage(String targetRoomId, ChatMessage msg) async {
    if (!_stompService.isConnected) return false;
    _stompService.sendMessage({
      'chatRoomId': targetRoomId,
      'userId': _userId,
      'username': _username,
      'content': '[전달] ${msg.username}: ${msg.content}',
      'type': 'CHAT',
      'priority': 'ROUTINE',
      'timestamp': DateTime.now().toIso8601String(),
    });
    return true;
  }

  void notifyTyping(String roomId) {
    _typingDebounce?.cancel();
    _typingDebounce = Timer(const Duration(milliseconds: 500), () {
      _stompService.sendTyping(roomId);
    });
  }

  void _onTypingReceived(String username) {
    final users = Set<String>.from(state.typingUsers)..add(username);
    state = state.copyWith(typingUsers: users);
    _typingTimers[username]?.cancel();
    _typingTimers[username] = Timer(const Duration(seconds: 3), () {
      if (!mounted) return;
      final updated = Set<String>.from(state.typingUsers)..remove(username);
      state = state.copyWith(typingUsers: updated);
      _typingTimers.remove(username);
    });
  }

  void disconnect() => _stompService.disconnect();

  @override
  void dispose() {
    _typingDebounce?.cancel();
    for (final t in _typingTimers.values) { t.cancel(); }
    _stompService.dispose();
    super.dispose();
  }
}

final chatNotifierProvider =
    StateNotifierProvider.autoDispose.family<ChatNotifier, ChatMessagesState, String>(
  (ref, roomId) {
    final auth = ref.watch(authProvider);
    final notifier = ChatNotifier(
      ref.watch(dioClientProvider),
      ref: ref,
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
