import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';
import '../../core/network/api_response.dart';
import '../../core/network/stomp_service.dart';
import '../../core/utils/server_time.dart';
import '../../shared/models/chat_message.dart';
import 'state/chat_messages_state.dart';
export 'state/chat_messages_state.dart';
import '../../core/constants/storage_keys.dart';
import '../auth/auth_provider.dart';
import 'chat_rooms_provider.dart';
import 'helpers/mention_candidates.dart';
import 'helpers/offline_message_queue.dart';
import 'internal/fcm_room_subscription.dart';
import 'internal/message_send_helper.dart';
import 'internal/read_state_helper.dart';
import 'internal/stomp_message_dispatcher.dart';
import 'helpers/typing_controller.dart';
import 'notification_policy_provider.dart';
import 'room_keywords_provider.dart';
import 'admin/admin_event_state.dart';
import 'admin/room_members_provider.dart';

/// Korean message for a refused join (`NOT_A_MEMBER` / `operation=addUser`).
String joinRejectedMessage(String reason) {
  switch (reason) {
    case 'ROOM_NOT_FOUND':
      return '채팅방을 찾을 수 없습니다.';
    case 'BANNED':
      return '이 채팅방에서 차단되어 입장할 수 없습니다.';
    case 'DM_NOT_PARTICIPANT':
      return '이 1:1 대화의 참여자가 아닙니다.';
    case 'PRIVATE_REQUIRES_INVITE':
      return '비공개 채팅방은 초대 링크로만 입장할 수 있습니다.';
    case 'PASSWORD_REQUIRED':
      return '비밀번호를 확인한 뒤 입장할 수 있습니다.';
    case 'ROOM_FULL':
      return '채팅방 정원이 가득 찼습니다.';
    default:
      return '이 채팅방에 입장할 수 없습니다.';
  }
}

class ChatNotifier extends StateNotifier<ChatMessagesState> {
  final DioClient _dioClient;
  final Ref _ref;
  final StompService _stompService;
  final String _token;
  final String _username;
  final String _userId;
  static const _storage = FlutterSecureStorage();
  final TypingController _typing = TypingController();

  /// localId → sending 타임아웃 타이머
  final Map<String, Timer> _sendingTimers = {};

  /// @mention 후보 조회 + 캐시. 키 입력마다 멤버 목록을 다시 받지 않는다.
  late final MentionCandidateSource _mentions =
      MentionCandidateSource(_dioClient.dio);
  final OfflineMessageQueue _offlineQueue = OfflineMessageQueue();
  String? _currentRoomId;

  /// Oldest timestamp loaded from the chat history endpoints: the `before`
  /// cursor for the next page. Kept apart from state.messages so summaries
  /// and live messages cannot move it.
  DateTime? _historyCursor;
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
    StompService? stompService,
  })  : _ref = ref,
        _stompService = stompService ?? StompService(),
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
      isActiveRoom: (roomId) => _ref.read(activeRoomIdProvider) == roomId,
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

    _historyCursor = null;

    // Fetch initial read positions for this room (non-blocking, best effort)
    _readState.fetchInitialReadPositions(roomId);

    // Load history
    try {
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages',
        queryParameters: {'size': 50},
      );
      if (!mounted) return;
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
      _advanceHistoryCursor(history);
      _mergeMessages(history);
      state = state.copyWith(
        isLoadingHistory: false,
        hasMoreHistory: history.length >= 50,
      );
    } catch (e) {
      debugPrint('[ChatNotifier] joinRoom error: $e');
      if (!mounted) return;
      state = state.copyWith(
          isLoadingHistory: false, errorMessage: '메시지를 불러올 수 없습니다.');
    }

    // Fetch last-read position and compute unread count for this session
    await _readState.fetchLastReadAndUpdateUnread(roomId);
    if (!mounted) return;
    // The entry-time markRoomRead ran before history arrived (empty id); now
    // persist the real last message so the server's lastRead moves.
    if (_ref.read(activeRoomIdProvider) == roomId) _readState.markRoomRead(roomId);

    // Set roomId before connect — _onMessage may fire before connect() returns
    _currentRoomId = roomId;

    // Connect WebSocket. A later connected=true is a reconnect: messages sent
    // while the socket was down never reach us over STOMP, so refetch them.
    var wasConnected = false;
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
            if (wasConnected) _refetchLatest(roomId);
            wasConnected = true;
            _send.flushOfflineQueue();
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
        // Membership changed, and this payload *is* the new list.
        _mentions.seed(_currentRoomId!, members);
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
      // Server refused addUser: the room is not joined. StompService has
      // already stopped reconnecting; the screen leaves on notMember.
      onJoinRejected: (rejectedRoomId, reason) {
        if (!mounted) return;
        _currentRoomId = null;
        state = state.copyWith(
          isConnected: false,
          exitReason: ChatExitReason.notMember,
          errorMessage: joinRejectedMessage(reason),
        );
      },
    );

    // Subscribe FCM token to room topic for push notifications (fire & forget)
    _subscribeFcmToRoom(roomId);
  }

  /// Re-reads the newest history page after a reconnect and merges it, so
  /// messages broadcast while the socket was down show up.
  Future<void> _refetchLatest(String roomId) async {
    try {
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages',
        queryParameters: {'size': 50},
      );
      if (!mounted || _currentRoomId != roomId) return;
      final latest = <ChatMessage>[];
      for (final e in apiResponseList(resp.data)) {
        try {
          latest.add(ChatMessage.fromJson(e as Map<String, dynamic>));
        } catch (parseErr) {
          debugPrint('[ChatNotifier] refetch parse error: $parseErr');
        }
      }
      _mergeMessages(latest);
    } catch (e) {
      debugPrint('[ChatNotifier] refetch after reconnect failed: $e');
    }
  }

  /// Reconnects the room socket now if it is down (app resumed).
  void reconnectIfNeeded() => _stompService.reconnectNow();

  Future<void> _subscribeFcmToRoom(String roomId) => _fcmSub.subscribe(roomId);
  Future<void> _unsubscribeFcmFromRoom(String roomId) =>
      _fcmSub.unsubscribe(roomId);

  Future<void> loadMoreHistory(String roomId) async {
    if (state.isLoadingHistory || !state.hasMoreHistory) return;
    state = state.copyWith(isLoadingHistory: true);
    try {
      final cursor = _historyCursor;
      final resp = await _dioClient.dio.get(
        '/api/chat/rooms/$roomId/messages/cursor',
        queryParameters: {
          'size': 50,
          if (cursor != null) 'before': cursor.toUtc().toIso8601String(),
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
      if (!mounted) return;
      _advanceHistoryCursor(newMessages);
      _mergeMessages(newMessages);
      state = state.copyWith(
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
  /// auto read-receipt.
  void _afterChatMessageReceived(ChatMessage msg) {
    // Auto read-receipt: mark as read when message arrives (user is viewing room)
    if (msg.userId != _userId && _currentRoomId != null) {
      _stompService.sendReadReceipt(_currentRoomId!, msg.effectiveId);
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
      if (!mounted) return false;
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
          editedAt: DateTime.now().toUtc().toIso8601String(),
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
      if (!mounted) return false;
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
  void mergeMessage(ChatMessage msg) => _mergeMessages([msg]);

  /// The one way fetched messages (history pages, summaries, thread
  /// replies) enter state. Messages already in state win over incoming
  /// copies (they may carry live edits/reactions). The timeline is
  /// re-sorted by parsed time and readCounts recomputed, since they are
  /// positional.
  void _mergeMessages(List<ChatMessage> incoming) {
    if (incoming.isEmpty) return;
    final kept = state.messages;
    final seen = {for (final m in kept) m.effectiveId};
    final merged = [
      ...kept,
      ...incoming.where((m) => seen.add(m.effectiveId)),
    ];
    final times = {for (final m in merged) m: _timeOf(m)};
    merged.sort((a, b) => times[a]!.compareTo(times[b]!));
    state = state.copyWith(
      messages: merged,
      readCounts: _readState.computeReadCounts(merged, state.readPositions),
    );
  }

  /// Moves the history cursor back to the oldest of [page].
  void _advanceHistoryCursor(List<ChatMessage> page) {
    for (final m in page) {
      final t = _timeOf(m);
      if (_historyCursor == null || t.isBefore(_historyCursor!)) {
        _historyCursor = t;
      }
    }
  }

  static DateTime _timeOf(ChatMessage m) =>
      parseServerDateTime(m.timestamp) ??
      DateTime.fromMillisecondsSinceEpoch(0);

  /// Called when user enters a room. Clears local unread count and persists last-read position.
  void markRoomRead(String roomId) => _readState.markRoomRead(roomId);

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
    ChatMessage? replyOverride,
  }) =>
      _send.sendMessage(
        roomId: roomId,
        content: content,
        replyOverride: replyOverride,
      );

  void retryFailedMessage(ChatMessage msg) => _send.retryFailedMessage(msg);

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

  /// @mention autocomplete candidates for [roomId], matching [query].
  /// See [MentionCandidateSource] for why this reads members, not presence.
  Future<List<Map<String, dynamic>>> searchMentionCandidates(
          String roomId, String query) =>
      _mentions.search(roomId, query);

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
    _stompService.dispose();
    super.dispose();
  }
}

final chatNotifierProvider = StateNotifierProvider.autoDispose
    .family<ChatNotifier, ChatMessagesState, String>(
  (ref, roomId) {
    // Only identity fields: unrelated auth changes (avatar, isLoading) must not
    // tear down the room socket and refetch history.
    final (token, userId, username) =
        ref.watch(authProvider.select((a) => (a.token, a.userId, a.username)));
    final notifier = ChatNotifier(
      ref.watch(dioClientProvider),
      ref: ref,
      token: token ?? '',
      username: username,
      userId: userId ?? '',
    );
    // Guard: only join when token is available (prevents empty-token WebSocket on auth hydration)
    if (token != null) {
      notifier.joinRoom(roomId);
    }
    // StateNotifier.dispose()가 _stompService.dispose()를 호출하므로 ref.onDispose 중복 등록 제거.
    // 이중 disconnect가 _manualDisconnect를 true로 고정시켜 재연결 불가 상태를 유발했던 버그 수정.
    return notifier;
  },
);
