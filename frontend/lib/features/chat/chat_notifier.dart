import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';
import '../../core/network/api_response.dart';
import '../../core/network/stomp_service.dart';
import '../../shared/models/chat_message.dart';
import '../../shared/models/patient_card.dart';
import 'state/chat_messages_state.dart';
export 'state/chat_messages_state.dart';
import '../../core/constants/storage_keys.dart';
import '../auth/auth_provider.dart';
import 'chat_rooms_provider.dart';
import 'helpers/offline_message_queue.dart';
import 'internal/fcm_room_subscription.dart';
import 'internal/message_send_helper.dart';
import 'internal/read_state_helper.dart';
import 'internal/stomp_message_dispatcher.dart';
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
  return apiResponseList(data)
      .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
      .toList();
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
  late final StompMessageDispatcher _dispatcher;
  late final ReadStateHelper _readState;
  late final MessageSendHelper _send;
  late final FcmRoomSubscription _fcmSub;

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
        super(const ChatMessagesState()) {
    _readState = ReadStateHelper(
      getCurrentState: () => state,
      setState: (s) => state = s,
      mounted: () => mounted,
      dioClient: _dioClient,
      userId: _userId,
      resetUnreadCount: (roomId) =>
          _ref.read(roomUnreadCountsProvider.notifier).reset(roomId),
      setUnreadCount: (roomId, count) =>
          _ref.read(roomUnreadCountsProvider.notifier).setCount(roomId, count),
    );
    _dispatcher = StompMessageDispatcher(
      getCurrentState: () => state,
      setState: (s) => state = s,
      mounted: () => mounted,
      userId: _userId,
      currentRoomId: () => _currentRoomId,
      computeReadCounts: _readState.computeReadCounts,
      onIncomingChatMessage: _afterChatMessageReceived,
      onPinChanged: () => _ref.read(chatRoomsProvider.notifier).fetchRooms(),
      onSendingConfirmed: (localId) {
        if (localId != null) _sendingTimers.remove(localId)?.cancel();
      },
    );
    _send = MessageSendHelper(
      getCurrentState: () => state,
      setState: (s) => state = s,
      mounted: () => mounted,
      userId: _userId,
      username: _username,
      stompService: _stompService,
      offlineQueue: _offlineQueue,
      sendingTimers: _sendingTimers,
      dioClient: _dioClient,
      clearReplyTarget: clearReplyTarget,
    );
    _fcmSub = FcmRoomSubscription(
      dioClient: _dioClient,
      getPolicyFor: (roomId) =>
          _ref.read(roomNotificationPolicyProvider.notifier).policyFor(roomId),
    );
  }

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
    _readState.fetchInitialReadPositions(roomId);

    // Load history
    try {
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages',
        queryParameters: {'size': 50},
      );
      final data = resp.data;
      final items = apiResponseList(data);
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
      state = state.copyWith(
          isLoadingHistory: false, errorMessage: '메시지를 불러올 수 없습니다.');
    }

    // Load AI summaries and merge into history
    await _fetchSummaries(roomId);

    // Fetch last-read position and compute unread count for this session
    await _readState.fetchLastReadAndUpdateUnread(roomId);

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
              // _localId is a client-only marker for optimistic-message dedup.
              // Strip it before forwarding so STOMP doesn't carry a contract-leak field.
              onSend: (msg) => _stompService.sendMessage(
                  Map<String, dynamic>.from(msg)..remove('_localId')),
            );
          }
        }
      },
      onReadReceipt: (positions) => _readState.handleReadReceipt(positions),
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
            _ref
                .read(chatRoomsProvider.notifier)
                .updateParticipantCount(_currentRoomId!, count);
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
      // Per-user non-fatal rejections (MUTED / NOT_A_MEMBER) — surface as a
      // transient SnackBar via the existing errorMessage path.
      onTransientError: (type, message) {
        if (!mounted) return;
        state = state.copyWith(errorMessage: message);
      },
    );

    // Subscribe FCM token to room topic for push notifications (fire & forget)
    _subscribeFcmToRoom(roomId);
  }

  Future<void> _subscribeFcmToRoom(String roomId) => _fcmSub.subscribe(roomId);
  Future<void> _unsubscribeFcmFromRoom(String roomId) =>
      _fcmSub.unsubscribe(roomId);

  Future<void> loadMoreHistory(String roomId) async {
    if (state.isLoadingHistory || !state.hasMoreHistory) return;
    state = state.copyWith(isLoadingHistory: true);
    try {
      final oldestTimestamp =
          state.messages.isNotEmpty ? state.messages.first.timestamp : null;
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages/cursor',
        queryParameters: {
          'size': 50,
          if (oldestTimestamp != null) 'before': oldestTimestamp,
        },
      );
      final data = resp.data;
      // Response: { data: { messages: [...], nextCursor: ..., hasMore: bool } }
      final inner = apiResponseMap(data);
      final List<dynamic> items;
      final bool? serverHasMore;
      if (inner != null && inner['messages'] is List) {
        items = inner['messages'] as List;
        serverHasMore = inner['hasMore'] as bool?;
      } else {
        items = [];
        serverHasMore = null;
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

  void _onMessage(Map<String, dynamic> rawMsg) => _dispatcher.dispatch(rawMsg);

  /// Post-append hook for genuinely new chat messages: sends an
  /// auto read-receipt and schedules a smart-reply refresh.
  void _afterChatMessageReceived(ChatMessage msg) {
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
            _ref.read(quickReplyProvider(_currentRoomId!).notifier).refresh(id);
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
      await _dioClient.dio
          .delete('/api/chat/rooms/$roomId/messages/$messageId');
      return true;
    } catch (_) {
      // Rollback on failure — restore original message list
      state = state.copyWith(messages: originalMessages);
      return false;
    }
  }

  Future<bool> editMessage(
      String roomId, String messageId, String newContent) async {
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
        debugPrint(
            'leaveRoom failed: status=${e.response?.statusCode} body=${e.response?.data}');
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
      final deduped = merged.where((m) => seen.add(m.effectiveId)).toList();
      deduped.sort((a, b) => a.timestamp.compareTo(b.timestamp));
      state = state.copyWith(messages: deduped);
    } catch (_) {
      // Best-effort — summary load must not interrupt chat
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
  void markRoomRead(String roomId) => _readState.markRoomRead(roomId);

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
      final msgJson = apiResponseMap(resp.data);
      if (msgJson != null) {
        final aiMsg = ChatMessage.fromJson(msgJson);
        if (!state.messages.any((m) => m.effectiveId == aiMsg.effectiveId)) {
          state = state.copyWith(
              messages: [...state.messages, aiMsg], isAiLoading: false);
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

  void clearError() {
    state = state.copyWith(clearErrorMessage: true);
  }

  void sendMessage({
    required String roomId,
    required String content,
    String priority = 'ROUTINE',
    ChatMessage? replyOverride,
  }) =>
      _send.sendMessage(
        roomId: roomId,
        content: content,
        priority: priority,
        replyOverride: replyOverride,
      );

  void retryFailedMessage(ChatMessage msg) => _send.retryFailedMessage(msg);

  void sendPatientCard(String roomId, PatientCard card) =>
      _send.sendPatientCard(roomId, card);

  Future<void> uploadAndSendFile({
    required String roomId,
    required String fileName,
    required Uint8List bytes,
    required String mimeType,
    String content = '',
  }) =>
      _send.uploadAndSendFile(
        roomId: roomId,
        fileName: fileName,
        bytes: bytes,
        mimeType: mimeType,
        content: content,
      );

  Future<void> toggleReaction(
      String roomId, String messageId, String emoji) async {
    try {
      await _dioClient.dio.post(
          '/api/chat/rooms/$roomId/messages/$messageId/reactions',
          data: {'emoji': emoji});
    } catch (e) {
      if (!mounted) return;
      String msg = '리액션을 추가할 수 없습니다.';
      if (e is DioException && e.response?.data is Map) {
        final body = e.response!.data as Map;
        final backendMsg = body['message']?.toString();
        if (backendMsg != null && backendMsg.isNotEmpty) msg = backendMsg;
      }
      state = state.copyWith(errorMessage: msg);
    }
  }

  Future<bool> forwardMessage(String targetRoomId, ChatMessage msg) =>
      _send.forwardMessage(targetRoomId, msg);

  Future<List<Map<String, dynamic>>> searchParticipants(
      String roomId, String query) async {
    try {
      final resp =
          await _dioClient.dio.get('/api/chat/rooms/$roomId/participants');
      final participants = apiResponseList(resp.data);
      final q = query.toLowerCase();
      return participants
          .where((p) =>
              (p['username']?.toString() ?? '').toLowerCase().contains(q))
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

  void disconnect() {
    _currentRoomId = null;
    _stompService.disconnect();
  }

  @override
  void dispose() {
    _typing.dispose();
    for (final t in _sendingTimers.values) {
      t.cancel();
    }
    _sendingTimers.clear();
    _quickReplyDebounce?.cancel();
    _stompService.dispose();
    super.dispose();
  }
}

final chatNotifierProvider = StateNotifierProvider.autoDispose
    .family<ChatNotifier, ChatMessagesState, String>(
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
