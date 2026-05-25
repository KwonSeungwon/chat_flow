import '../../../shared/models/chat_message.dart';

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
