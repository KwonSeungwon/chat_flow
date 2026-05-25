import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_message.dart';
import '../state/chat_messages_state.dart';

/// Internal helper for read-state operations on [ChatNotifier].
///
/// Encapsulates read-receipt tracking, unread-count computation, and
/// last-read position persistence.  State mutations go through
/// [getCurrentState]/[setState] closures so that every write still
/// routes through the owning [StateNotifier]'s setter.
class ReadStateHelper {
  ReadStateHelper({
    required this.getCurrentState,
    required this.setState,
    required this.mounted,
    required this.dioClient,
    required this.userId,
    required this.readUnreadCounts,
    required this.writeUnreadCounts,
  });

  /// Returns the live [ChatMessagesState] snapshot.
  final ChatMessagesState Function() getCurrentState;

  /// Writes a new [ChatMessagesState].
  final void Function(ChatMessagesState) setState;

  /// Whether the owning [StateNotifier] is still mounted.
  final bool Function() mounted;

  /// HTTP client for REST calls (last-read persistence, initial positions).
  final DioClient dioClient;

  /// Current user's ID — excluded from read-position maps so the
  /// user's own messages don't count towards their own read receipts.
  final String userId;

  /// Reads the current global `roomUnreadCountsProvider` state.
  final Map<String, int> Function() readUnreadCounts;

  /// Writes a new value to the global `roomUnreadCountsProvider`.
  final void Function(Map<String, int>) writeUnreadCounts;

  // -------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------

  /// Called when user enters a room.  Clears local unread count and
  /// persists last-read position to the server.
  void markRoomRead(String roomId) {
    final current = Map<String, int>.from(readUnreadCounts());
    current[roomId] = 0;
    writeUnreadCounts(current);

    // 항상 서버에 readAt을 갱신 — 메시지가 아직 로드되지 않았어도 빈 lastReadMessageId로 호출
    final chatMsgs =
        getCurrentState().messages.where((m) => m.type == 'CHAT').toList();
    final lastReadId = chatMsgs.isNotEmpty ? chatMsgs.last.effectiveId : '';
    _persistLastRead(roomId, lastReadId);
  }

  /// Handles an incoming STOMP read-receipt broadcast.  Filters out
  /// the current user's own position and recomputes per-message read
  /// counts.
  void handleReadReceipt(Map<String, String> positions) {
    if (!mounted()) return;
    // 본인 id 제외한 다른 참여자의 포지션만 유지
    final filtered = <String, String>{};
    positions.forEach((uid, msgId) {
      if (uid != userId && msgId.isNotEmpty) filtered[uid] = msgId;
    });
    setState(getCurrentState().copyWith(
      readPositions: filtered,
      readCounts: computeReadCounts(getCurrentState().messages, filtered),
    ));
  }

  /// Fetches the user's last-read position from the backend and
  /// computes the initial unread count for the room.
  Future<void> fetchLastReadAndUpdateUnread(String roomId) async {
    try {
      final resp =
          await dioClient.dio.get('/api/chat/rooms/$roomId/last-read');
      final data = resp.data;
      final lastReadId = data is Map
          ? ((data['data'] as Map?)?['lastReadMessageId']?.toString() ?? '')
          : '';
      if (!mounted()) return;

      // Find how many CHAT messages are after the lastRead position
      int unreadCount = 0;
      if (lastReadId.isNotEmpty) {
        final chatMsgs =
            getCurrentState().messages.where((m) => m.type == 'CHAT').toList();
        final idx =
            chatMsgs.indexWhere((m) => m.effectiveId == lastReadId);
        if (idx >= 0 && idx < chatMsgs.length - 1) {
          unreadCount = chatMsgs.length - idx - 1;
        }
        setState(getCurrentState().copyWith(lastReadMessageId: lastReadId));
      }

      // Update global unread counts map
      final current = Map<String, int>.from(readUnreadCounts());
      current[roomId] = unreadCount;
      writeUnreadCounts(current);
    } catch (_) {
      // Non-critical — best effort
    }
  }

  /// Fetches the initial read positions for all participants in the
  /// room and computes per-message read counts.
  Future<void> fetchInitialReadPositions(String roomId) async {
    try {
      final resp =
          await dioClient.dio.get('/api/chat/rooms/$roomId/readers');
      final data = resp.data;
      if (data is Map && data['data'] is Map) {
        final raw = data['data'] as Map;
        final positions = <String, String>{};
        raw.forEach((k, v) {
          final uid = k.toString();
          final mid = v?.toString() ?? '';
          if (uid != userId && mid.isNotEmpty) positions[uid] = mid;
        });
        if (!mounted()) return;
        setState(getCurrentState().copyWith(
          readPositions: positions,
          readCounts: computeReadCounts(getCurrentState().messages, positions),
        ));
      }
    } catch (_) {
      // best-effort
    }
  }

  /// readPositions(userId -> lastReadMessageId)와 messages 타임라인으로부터
  /// 메시지별 readCount 맵을 계산.  messageId가 특정 메시지 이후(또는 동일)면
  /// 해당 사용자는 그 메시지를 읽은 것.
  Map<String, int> computeReadCounts(
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

  // -------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------

  Future<void> _persistLastRead(
      String roomId, String lastReadMessageId) async {
    try {
      await dioClient.dio.put(
        '/api/chat/rooms/$roomId/last-read',
        data: {'lastReadMessageId': lastReadMessageId},
      );
    } catch (_) {
      // Best-effort — failure must not interrupt room viewing
    }
  }
}
