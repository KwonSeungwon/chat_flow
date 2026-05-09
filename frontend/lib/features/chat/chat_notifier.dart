import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';
import '../../core/network/stomp_service.dart';
import '../../core/services/fcm_service.dart';
import '../../shared/models/chat_message.dart';
import '../../shared/models/patient_card.dart';
import '../../core/constants/storage_keys.dart';
import '../auth/auth_provider.dart';
import 'chat_rooms_provider.dart';
import 'helpers/offline_message_queue.dart';
import 'helpers/typing_controller.dart';
import 'notification_policy_provider.dart';
import 'quick_reply_provider.dart';
import 'room_keywords_provider.dart';
import 'admin/admin_event_state.dart';
import 'admin/room_members_provider.dart';

/// Parse a `/api/ai-summary/room/{id}` response into a list of summaries.
///
/// Accepts three shapes:
/// - ApiResponse-wrapped: `{success, data: [...], message}` — the canonical
///   shape after the backend was unified to use ApiResponse.
/// - Bare list: `[...]` — kept for backward compatibility with cached
///   responses or older deployments.
/// - Anything else (error envelope, null, malformed) → empty list.
List<ChatMessage> parseSummariesResponse(dynamic data) {
  if (data is Map && data['data'] is List) {
    return (data['data'] as List)
        .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
        .toList();
  }
  if (data is List) {
    return data
        .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
        .toList();
  }
  return const <ChatMessage>[];
}

// ---------------------------------------------------------------------------
// Chat Messages
// ---------------------------------------------------------------------------

enum ChatExitReason { none, deleted, full }

class ChatMessagesState {
  final List<ChatMessage> messages;
  final bool isConnected;
  final bool wasEverConnected;
  final bool isLoadingHistory;
  final bool isAiLoading;
  final bool isSummaryLoading;
  final bool hasMoreHistory;
  final String? errorMessage;
  /// messageId → read count (파생값 — readPositions로부터 계산)
  final Map<String, int> readCounts;
  /// userId → 해당 사용자의 lastReadMessageId (room 전체 읽음 상태)
  final Map<String, String> readPositions;
  /// Last message the current user has read (fetched from backend on join)
  final String? lastReadMessageId;
  final ChatMessage? replyTarget;
  final ChatExitReason exitReason;
  /// For ROOM_FULL: redirectTo room ID
  final String? redirectTo;
  final Set<String> typingUsers;
  /// Real-time participant count from presence events
  final int? participantCount;

  const ChatMessagesState({
    this.messages = const [],
    this.isConnected = false,
    this.wasEverConnected = false,
    this.isLoadingHistory = false,
    this.isAiLoading = false,
    this.isSummaryLoading = false,
    this.hasMoreHistory = true,
    this.errorMessage,
    this.readCounts = const {},
    this.readPositions = const {},
    this.lastReadMessageId,
    this.replyTarget,
    this.exitReason = ChatExitReason.none,
    this.redirectTo,
    this.typingUsers = const {},
    this.participantCount,
  });

  ChatMessagesState copyWith({
    List<ChatMessage>? messages,
    bool? isConnected,
    bool? wasEverConnected,
    bool? isLoadingHistory,
    bool? isAiLoading,
    bool? isSummaryLoading,
    bool? hasMoreHistory,
    String? errorMessage,
    bool clearErrorMessage = false,
    Map<String, int>? readCounts,
    Map<String, String>? readPositions,
    String? lastReadMessageId,
    bool clearLastReadMessageId = false,
    ChatMessage? replyTarget,
    bool clearReplyTarget = false,
    ChatExitReason? exitReason,
    String? redirectTo,
    bool clearRedirectTo = false,
    Set<String>? typingUsers,
    int? participantCount,
  }) {
    final newConnected = isConnected ?? this.isConnected;
    return ChatMessagesState(
      messages: messages ?? this.messages,
      isConnected: newConnected,
      wasEverConnected: (wasEverConnected ?? this.wasEverConnected) || newConnected,
      isLoadingHistory: isLoadingHistory ?? this.isLoadingHistory,
      isAiLoading: isAiLoading ?? this.isAiLoading,
      isSummaryLoading: isSummaryLoading ?? this.isSummaryLoading,
      hasMoreHistory: hasMoreHistory ?? this.hasMoreHistory,
      errorMessage: clearErrorMessage ? null : (errorMessage ?? this.errorMessage),
      readCounts: readCounts ?? this.readCounts,
      readPositions: readPositions ?? this.readPositions,
      lastReadMessageId: clearLastReadMessageId ? null : (lastReadMessageId ?? this.lastReadMessageId),
      replyTarget: clearReplyTarget ? null : (replyTarget ?? this.replyTarget),
      exitReason: exitReason ?? this.exitReason,
      redirectTo: clearRedirectTo ? null : (redirectTo ?? this.redirectTo),
      typingUsers: typingUsers ?? this.typingUsers,
      participantCount: participantCount ?? this.participantCount,
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
  final TypingController _typing = TypingController();
  /// localId → sending 타임아웃 타이머
  final Map<String, Timer> _sendingTimers = {};
  final OfflineMessageQueue _offlineQueue = OfflineMessageQueue();
  String? _currentRoomId;
  Timer? _quickReplyDebounce;

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
      hasMoreHistory: true,
      clearErrorMessage: true,
      readCounts: {},
      readPositions: {},
      clearLastReadMessageId: true,
      exitReason: ChatExitReason.none,
      clearRedirectTo: true,
    );

    // Fetch initial read positions for this room (non-blocking, best effort)
    _fetchInitialReadPositions(roomId);

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
      final history = <ChatMessage>[];
      for (final e in items) {
        try {
          history.add(ChatMessage.fromJson(e as Map<String, dynamic>));
        } catch (parseErr) {
          debugPrint('[ChatNotifier] Message parse error: $parseErr');
        }
      }
      history.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      // Merge history with any live STOMP messages already received
      final live = state.messages;
      final merged = [...history, ...live];
      final seen = <String>{};
      final deduped = merged.where((m) => seen.add(m.effectiveId)).toList();
      deduped.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      state = state.copyWith(
        messages: deduped,
        isLoadingHistory: false,
        hasMoreHistory: history.length >= 50,
      );
    } catch (e) {
      debugPrint('[ChatNotifier] joinRoom error: $e');
      state = state.copyWith(isLoadingHistory: false, errorMessage: '메시지를 불러올 수 없습니다.');
    }

    // Load AI summaries and merge into history
    await _fetchSummaries(roomId);

    // Fetch last-read position and compute unread count for this session
    await _fetchLastReadAndUpdateUnread(roomId);

    // Set roomId before connect — _onMessage may fire before connect() returns
    _currentRoomId = roomId;

    // Connect WebSocket
    _stompService.connect(
      roomId: roomId,
      username: _username,
      userId: _userId,
      token: _token,
      tokenProvider: () async =>
          await _storage.read(key: StorageKeys.token) ?? _token,
      onMessage: (msg) => _onMessage(msg),
      onConnectionChanged: (connected) {
        if (mounted) {
          state = state.copyWith(isConnected: connected);
          if (connected) {
            _offlineQueue.flush(
              onDedup: (ids) {
                if (!mounted) return;
                final cleaned = state.messages
                    .where((m) => m.localId == null || !ids.contains(m.localId))
                    .toList();
                state = state.copyWith(messages: cleaned);
              },
              onSend: (msg) => _stompService.sendMessage(msg),
            );
          }
        }
      },
      onReadReceipt: (positions) {
        if (!mounted) return;
        // 본인 id 제외한 다른 참여자의 포지션만 유지 (본인 메시지에 본인이 카운트되지 않도록)
        final filtered = <String, String>{};
        positions.forEach((uid, msgId) {
          if (uid != _userId && msgId.isNotEmpty) filtered[uid] = msgId;
        });
        state = state.copyWith(
          readPositions: filtered,
          readCounts: _computeReadCounts(state.messages, filtered),
        );
      },
      onTyping: (username, {bool stop = false}) {
        if (!mounted) return;
        _onTypingReceived(username, stop: stop);
      },
      onPresence: (type, username, count) {
        if (!mounted) return;
        state = state.copyWith(participantCount: count);
        // Sync participant count to room list for sidebar display
        if (_currentRoomId != null) {
          try {
            _ref.read(chatRoomsProvider.notifier).updateParticipantCount(_currentRoomId!, count);
          } catch (_) {}
        }
      },
      onRoomFull: (redirectTo, roomName) {
        if (mounted) {
          state = state.copyWith(
            exitReason: ChatExitReason.full,
            redirectTo: redirectTo,
          );
        }
      },
      onMembersUpdate: (members) {
        if (!mounted || _currentRoomId == null) return;
        try {
          _ref
              .read(roomMembersProvider(_currentRoomId!).notifier)
              .applyMembersUpdate(members);
        } catch (_) {}
      },
      onKicked: (reason, byUserId, byUsername) {
        if (!mounted || _currentRoomId == null) return;
        _ref.read(kickedEventProvider.notifier).state = KickedEvent(
          roomId: _currentRoomId!,
          reason: reason,
          by: byUsername,
        );
      },
      onMuted: (mutedUntil, byUserId, byUsername) {
        if (!mounted || _currentRoomId == null) return;
        _ref.read(mutedEventProvider(_currentRoomId!).notifier).state =
            MutedEvent(
          roomId: _currentRoomId!,
          mutedUntil: mutedUntil,
          by: byUsername,
        );
      },
      onBanned: (bannedRoomId) {
        if (!mounted) return;
        _ref.read(kickedEventProvider.notifier).state = KickedEvent(
          roomId: bannedRoomId,
          reason: 'BANNED',
        );
      },
    );

    // Subscribe FCM token to room topic for push notifications (fire & forget)
    _subscribeFcmToRoom(roomId);
  }

  Future<void> _subscribeFcmToRoom(String roomId) async {
    try {
      final policy = _ref.read(roomNotificationPolicyProvider.notifier).policyFor(roomId);
      if (policy != NotificationPolicy.all) return; // policy says skip room topic
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

  /// Best-effort unsubscribe from a room's FCM topic. Mirrors
  /// _subscribeFcmToRoom — called from leaveRoom so push notifications stop
  /// arriving once the user has explicitly left.
  Future<void> _unsubscribeFcmFromRoom(String roomId) async {
    try {
      final token = await FcmService.getToken();
      if (token == null) return;
      await _dioClient.dio.delete('/api/fcm/subscribe', data: {
        'token': token,
        'roomId': roomId,
      });
    } catch (_) {
      // Best-effort — FCM failure must not interrupt room leave
    }
  }

  Future<void> loadMoreHistory(String roomId) async {
    if (state.isLoadingHistory || !state.hasMoreHistory) return;
    state = state.copyWith(isLoadingHistory: true);
    try {
      final oldestTimestamp = state.messages.isNotEmpty ? state.messages.first.timestamp : null;
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages/cursor',
        queryParameters: {
          'size': 50,
          if (oldestTimestamp != null) 'before': oldestTimestamp,
        },
      );
      final data = resp.data;
      // Response: { data: { messages: [...], nextCursor: ..., hasMore: bool } }
      List<dynamic> items;
      bool? serverHasMore;
      if (data is Map && data['data'] is Map) {
        final inner = data['data'] as Map;
        items = (inner['messages'] as List?) ?? [];
        serverHasMore = inner['hasMore'] as bool?;
      } else if (data is Map && data['messages'] is List) {
        items = data['messages'] as List;
        serverHasMore = data['hasMore'] as bool?;
      } else {
        items = [];
      }
      final newMessages = <ChatMessage>[];
      for (final e in items) {
        try {
          newMessages.add(ChatMessage.fromJson(e as Map<String, dynamic>));
        } catch (parseErr) {
          debugPrint('[ChatNotifier] loadMoreHistory parse error: $parseErr');
        }
      }
      newMessages.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      final merged = [...newMessages, ...state.messages];
      final seen = <String>{};
      final deduped = merged.where((m) => seen.add(m.effectiveId)).toList();
      deduped.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      if (!mounted) return;
      state = state.copyWith(
        messages: deduped,
        isLoadingHistory: false,
        hasMoreHistory: serverHasMore ?? newMessages.length >= 50,
      );
    } catch (e) {
      debugPrint('[ChatNotifier] loadMoreHistory error: $e');
      if (mounted) state = state.copyWith(isLoadingHistory: false);
    }
  }

  void _onMessage(Map<String, dynamic> rawMsg) {
    if (!mounted) return;
    final type = rawMsg['type']?.toString().toUpperCase();

    if (type == 'ROOM_DELETED') {
      state = state.copyWith(exitReason: ChatExitReason.deleted);
      return;
    }

    // Handle soft-deleted message broadcast
    if (type == 'MESSAGE_DELETED') {
      final deletedId = rawMsg['messageId']?.toString();
      if (deletedId == null) return;
      final updated = state.messages.map((m) {
        if (m.effectiveId == deletedId || m.messageId == deletedId) {
          return m.copyWith(content: '삭제된 메시지입니다.', deleted: true);
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
          return m.copyWith(content: newContent, edited: true, editedAt: editedAt);
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
          return m.copyWith(reactions: reactions);
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
    // Replace local 'sending' message with server-confirmed version
    final sendingIdx = existing.indexWhere((m) =>
        m.deliveryStatus == MessageDeliveryStatus.sending &&
        m.userId == msg.userId &&
        m.content == msg.content);
    if (sendingIdx >= 0) {
      final replaced = List<ChatMessage>.from(existing);
      replaced[sendingIdx] = msg;
      // 서버 확인됨 — 해당 localId의 타임아웃 취소
      final localId = existing[sendingIdx].localId;
      if (localId != null) {
        _sendingTimers.remove(localId)?.cancel();
      }
      state = state.copyWith(messages: replaced);
      return;
    }
    // Dedup by effectiveId
    if (existing.any((m) => m.effectiveId == msg.effectiveId)) return;
    final updated = [...existing, msg];
    // Cap at 500
    final capped =
        updated.length > 500 ? updated.sublist(updated.length - 500) : updated;
    // 새 메시지가 추가되면 타임라인이 변하므로 readCounts 재계산
    state = state.copyWith(
      messages: capped,
      readCounts: _computeReadCounts(capped, state.readPositions),
    );
    // Auto read-receipt: mark as read when message arrives (user is viewing room)
    if (msg.userId != _userId && _currentRoomId != null) {
      _stompService.sendReadReceipt(_currentRoomId!, msg.effectiveId);
    }
    // Smart Reply: refresh suggestions when a non-self HUMAN message arrives.
    // AI summaries / Q&A responses must be excluded — otherwise the AI is
    // asked to suggest replies to its own message, which wastes a Gemini call
    // and produces nonsensical chips.
    final isAi = msg.type == 'AI_SUMMARY' || msg.isAiGenerated;
    if (msg.userId != _userId && _currentRoomId != null && !isAi) {
      final id = msg.messageId ?? msg.localId ?? '';
      if (id.isNotEmpty) {
        _quickReplyDebounce?.cancel();
        _quickReplyDebounce = Timer(const Duration(seconds: 1), () {
          if (!mounted || _currentRoomId == null) return;
          try {
            _ref
                .read(quickReplyProvider(_currentRoomId!).notifier)
                .refresh(id);
          } catch (_) {/* best-effort */}
        });
      }
    }
  }

  Future<bool> deleteMessage(String roomId, String messageId) async {
    final originalMessages = List<ChatMessage>.from(state.messages);
    // Optimistic update
    final updated = state.messages.map((m) {
      if (m.effectiveId == messageId || m.messageId == messageId) {
        return m.copyWith(content: '삭제된 메시지입니다.', deleted: true);
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
        return m.copyWith(
          content: newContent,
          edited: true,
          editedAt: DateTime.now().toIso8601String(),
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
      // Stop FCM push for this room (mirror of joinRoom's _subscribeFcmToRoom)
      await _unsubscribeFcmFromRoom(roomId);
      _stompService.disconnect();
      state = const ChatMessagesState();
      // Clean up per-room storage for the left room
      _ref.read(roomKeywordsProvider.notifier).removeRoom(roomId);
      _ref.read(roomNotificationPolicyProvider.notifier).removeRoom(roomId);
      // 사이드바에서 나간 방이 즉시 제거되도록 방 목록 재조회
      _ref.read(chatRoomsProvider.notifier).fetchRooms();
      return true;
    } catch (e) {
      if (e is DioException) {
        debugPrint('leaveRoom failed: status=${e.response?.statusCode} body=${e.response?.data}');
      } else {
        debugPrint('leaveRoom failed: $e');
      }
      return false;
    }
  }

  Future<void> _fetchSummaries(String roomId) async {
    try {
      final resp = await _dioClient.dio.get('/api/ai-summary/room/$roomId');
      final summaries = parseSummariesResponse(resp.data);
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

  /// Number of replies in the currently loaded message buffer for a given
  /// parent. Used to render the reply chip on parent messages — approximate
  /// (only counts what's loaded). The thread panel fetches the authoritative
  /// list from the backend on open.
  int replyCountFor(String parentMessageId) {
    if (parentMessageId.isEmpty) return 0;
    return state.messages.where((m) {
      final pid = m.parentMessageId;
      return pid != null && pid == parentMessageId && !m.deleted;
    }).length;
  }

  /// Inserts a message into state if not already present (by effectiveId).
  /// Used by ThreadPanel to seed state with server-fetched replies that may
  /// not have arrived via STOMP yet.
  void mergeMessage(ChatMessage msg) {
    final existing = state.messages;
    if (existing.any((m) => m.effectiveId == msg.effectiveId)) return;
    final updated = [...existing, msg]
      ..sort((a, b) => a.timestamp.compareTo(b.timestamp));
    state = state.copyWith(messages: updated);
  }

  /// Called when user enters a room. Clears local unread count and persists last-read position.
  /// 메시지 미로드 상태에서도 서버 readAt을 NOW로 갱신해야 sidebar timer 폴링이 count를 덮어쓰지 않음.
  void markRoomRead(String roomId) {
    final current = Map<String, int>.from(_ref.read(roomUnreadCountsProvider));
    current[roomId] = 0;
    _ref.read(roomUnreadCountsProvider.notifier).state = current;

    // 항상 서버에 readAt을 갱신 — 메시지가 아직 로드되지 않았어도 빈 lastReadMessageId로 호출
    final chatMsgs = state.messages.where((m) => m.type == 'CHAT').toList();
    final lastReadId = chatMsgs.isNotEmpty ? chatMsgs.last.effectiveId : '';
    _persistLastRead(roomId, lastReadId);
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

  void sendMessage({
    required String roomId,
    required String content,
    String priority = 'ROUTINE',
    ChatMessage? replyOverride,
  }) {
    // replyOverride lets ThreadPanel post replies without mutating
    // state.replyTarget (which is owned by the main chat input).
    final reply = replyOverride ?? state.replyTarget;
    final localId = const Uuid().v4();
    final msg = {
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': content,
      'type': 'CHAT',
      'priority': priority,
      'timestamp': DateTime.now().toIso8601String(),
      if (reply != null) 'parentMessageId': reply.effectiveId,
      '_localId': localId,
    };
    // Show local message immediately with 'sending' status
    final localMsg = ChatMessage(
      chatRoomId: roomId, userId: _userId, username: _username,
      content: content, type: 'CHAT', priority: priority,
      timestamp: msg['timestamp']!,
      parentMessageId: reply?.effectiveId,
      localId: localId,
      deliveryStatus: MessageDeliveryStatus.sending,
    );
    state = state.copyWith(messages: [...state.messages, localMsg]);
    if (_stompService.isConnected) {
      final sendPayload = Map<String, dynamic>.from(msg)..remove('_localId');
      _stompService.sendMessage(sendPayload);
    } else {
      _offlineQueue.enqueue(msg);
    }
    // Only clear when the reply came from state (main chat input).
    // Override callers (ThreadPanel) manage their own state.
    if (reply != null && replyOverride == null) clearReplyTarget();
    // 10초 내 서버 확인(동일 localId 메시지가 sent로 교체) 없으면 failed로 표시
    _sendingTimers[localId] = Timer(const Duration(seconds: 10), () {
      _sendingTimers.remove(localId);
      if (!mounted) return;
      final idx = state.messages.indexWhere((m) =>
          m.localId == localId && m.deliveryStatus == MessageDeliveryStatus.sending);
      if (idx < 0) return;
      final m = state.messages[idx];
      final failed = m.copyWith(deliveryStatus: MessageDeliveryStatus.failed);
      final list = List<ChatMessage>.from(state.messages);
      list[idx] = failed;
      state = state.copyWith(messages: list);
    });
  }

  /// 실패한 메시지 재전송. 기존 localMsg를 제거하고 sendMessage 재호출.
  void retryFailedMessage(ChatMessage msg) {
    if (msg.deliveryStatus != MessageDeliveryStatus.failed) return;
    final list = state.messages.where((m) => m.localId != msg.localId).toList();
    state = state.copyWith(messages: list);
    sendMessage(
      roomId: msg.chatRoomId,
      content: msg.content,
      priority: msg.priority,
    );
  }

  Future<void> _fetchInitialReadPositions(String roomId) async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms/$roomId/readers');
      final data = resp.data;
      if (data is Map && data['data'] is Map) {
        final raw = data['data'] as Map;
        final positions = <String, String>{};
        raw.forEach((k, v) {
          final uid = k.toString();
          final mid = v?.toString() ?? '';
          if (uid != _userId && mid.isNotEmpty) positions[uid] = mid;
        });
        if (!mounted) return;
        state = state.copyWith(
          readPositions: positions,
          readCounts: _computeReadCounts(state.messages, positions),
        );
      }
    } catch (_) {
      // best-effort
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
    final isFile = msg.isFileMessage;
    final content = '[전달] ${msg.username}: ${msg.content}';
    final forwardedFrom = '${msg.username}: ${msg.content.length > 100 ? '${msg.content.substring(0, 100)}...' : msg.content}';

    if (_stompService.isConnected) {
      _stompService.sendMessage({
        'chatRoomId': targetRoomId,
        'userId': _userId,
        'username': _username,
        'content': content,
        'type': isFile ? 'FILE' : 'CHAT',
        'priority': 'ROUTINE',
        'timestamp': DateTime.now().toIso8601String(),
        'forwardedFrom': forwardedFrom,
        if (isFile) 'fileUrl': msg.fileUrl,
        if (isFile) 'fileName': msg.fileName,
        if (isFile) 'fileContentType': msg.fileContentType,
      });
      return true;
    }
    // REST fallback when STOMP is disconnected
    try {
      await _dioClient.dio.post(
        '/api/chat/rooms/$targetRoomId/messages',
        data: {'content': content, 'forwardedFrom': forwardedFrom},
      );
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<List<Map<String, dynamic>>> searchParticipants(String roomId, String query) async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms/$roomId/participants');
      final data = resp.data;
      List<dynamic> participants = [];
      if (data is Map && data['data'] is List) {
        participants = data['data'] as List;
      } else if (data is List) {
        participants = data;
      }
      final q = query.toLowerCase();
      return participants
          .where((p) => (p['username']?.toString() ?? '').toLowerCase().contains(q))
          .map((p) => Map<String, dynamic>.from(p as Map))
          .toList();
    } catch (_) {
      return [];
    }
  }

  void notifyTyping(String roomId) {
    _typing.scheduleSend(() => _stompService.sendTyping(roomId));
  }

  void _onTypingReceived(String username, {bool stop = false}) {
    _typing.markTyping(
      username,
      stop: stop,
      onAdd: () {
        if (!mounted) return;
        final users = Set<String>.from(state.typingUsers)..add(username);
        state = state.copyWith(typingUsers: users);
      },
      onRemove: () {
        if (!mounted) return;
        final updated = Set<String>.from(state.typingUsers)..remove(username);
        state = state.copyWith(typingUsers: updated);
      },
    );
  }

  /// readPositions(userId → lastReadMessageId)와 messages 타임라인으로부터
  /// 메시지별 readCount 맵을 계산. messageId가 특정 메시지 이후(또는 동일)면 해당 사용자는 그 메시지를 읽은 것.
  Map<String, int> _computeReadCounts(
      List<ChatMessage> messages, Map<String, String> positions) {
    if (messages.isEmpty || positions.isEmpty) return const {};
    final indexById = <String, int>{};
    for (int i = 0; i < messages.length; i++) {
      final id = messages[i].effectiveId;
      if (id.isNotEmpty) indexById[id] = i;
    }
    // 각 사용자의 마지막 읽은 메시지 인덱스
    final userReadIndexes = <int>[];
    positions.forEach((_, msgId) {
      final idx = indexById[msgId];
      if (idx != null) userReadIndexes.add(idx);
    });
    if (userReadIndexes.isEmpty) return const {};
    // 메시지 i에 대해 readIndex >= i 인 사용자 수를 계산
    final counts = <String, int>{};
    for (int i = 0; i < messages.length; i++) {
      final id = messages[i].effectiveId;
      if (id.isEmpty) continue;
      int c = 0;
      for (final idx in userReadIndexes) {
        if (idx >= i) c++;
      }
      if (c > 0) counts[id] = c;
    }
    return counts;
  }

  void disconnect() {
    _currentRoomId = null;
    _stompService.disconnect();
  }

  @override
  void dispose() {
    _typing.dispose();
    for (final t in _sendingTimers.values) { t.cancel(); }
    _sendingTimers.clear();
    _quickReplyDebounce?.cancel();
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
    // StateNotifier.dispose()가 _stompService.dispose()를 호출하므로 ref.onDispose 중복 등록 제거.
    // 이중 disconnect가 _manualDisconnect를 true로 고정시켜 재연결 불가 상태를 유발했던 버그 수정.
    return notifier;
  },
);
