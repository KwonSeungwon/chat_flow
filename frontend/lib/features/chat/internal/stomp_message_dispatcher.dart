import '../../../shared/models/chat_message.dart';
import '../state/chat_messages_state.dart';

/// Internal helper that translates STOMP payload events into
/// [ChatMessagesState] mutations.  Owned by [ChatNotifier] and invoked
/// via closures over its mutable state so that every `state = ...`
/// write still goes through the [StateNotifier] setter.
class StompMessageDispatcher {
  StompMessageDispatcher({
    required this.getCurrentState,
    required this.setState,
    required this.mounted,
    required this.userId,
    required this.currentRoomId,
    required this.computeReadCounts,
    required this.onIncomingChatMessage,
    required this.onPinChanged,
    required this.onSendingConfirmed,
  });

  /// Returns the live [ChatMessagesState] snapshot (closure over
  /// `() => state`).
  final ChatMessagesState Function() getCurrentState;

  /// Writes a new [ChatMessagesState] (closure over
  /// `(s) => state = s`).
  final void Function(ChatMessagesState) setState;

  /// Whether the owning [StateNotifier] is still mounted.
  final bool Function() mounted;

  /// Current user's ID — used for dedup of sending-status messages.
  final String userId;

  /// Returns the room ID the notifier is currently joined to.
  final String? Function() currentRoomId;

  /// Recomputes per-message read counts from the message timeline and
  /// reader positions.
  final Map<String, int> Function(
    List<ChatMessage> messages,
    Map<String, String> readPositions,
  ) computeReadCounts;

  /// Called after a genuinely new chat message has been appended to
  /// state (auto read-receipt + smart-reply scheduling).
  final void Function(ChatMessage msg) onIncomingChatMessage;

  /// Called when a pin/unpin broadcast arrives so the room list can
  /// refresh.
  final void Function() onPinChanged;

  /// Called when a server-confirmed message replaces a local
  /// 'sending'-status message.  The [String?] is the localId of the
  /// matched optimistic message (used to cancel the timeout timer).
  final void Function(String? localId) onSendingConfirmed;

  // -------------------------------------------------------------------
  // Public entry point — called from ChatNotifier._onMessage
  // -------------------------------------------------------------------

  void dispatch(Map<String, dynamic> rawMsg) {
    if (!mounted()) return;
    final type = rawMsg['type']?.toString().toUpperCase();

    if (type == 'ROOM_DELETED') {
      setState(getCurrentState().copyWith(exitReason: ChatExitReason.deleted));
      return;
    }

    // Handle soft-deleted message broadcast
    if (type == 'MESSAGE_DELETED') {
      _handleMessageDeleted(rawMsg);
      return;
    }

    if (type == 'MESSAGE_EDITED') {
      _handleMessageEdited(rawMsg);
      return;
    }

    if (type == 'REACTION_UPDATED') {
      _handleReactionUpdated(rawMsg);
      return;
    }

    if (type == 'MESSAGE_PINNED' || type == 'MESSAGE_UNPINNED') {
      // Refresh room list to update pinnedMessageId
      onPinChanged();
      return;
    }

    _handleNewMessage(rawMsg);
  }

  // -------------------------------------------------------------------
  // Private handlers
  // -------------------------------------------------------------------

  void _handleMessageDeleted(Map<String, dynamic> rawMsg) {
    final deletedId = rawMsg['messageId']?.toString();
    if (deletedId == null) return;
    final updated = getCurrentState().messages.map((m) {
      if (m.effectiveId == deletedId || m.messageId == deletedId) {
        return m.copyWith(content: '삭제된 메시지입니다.', deleted: true);
      }
      return m;
    }).toList();
    setState(getCurrentState().copyWith(messages: updated));
  }

  void _handleMessageEdited(Map<String, dynamic> rawMsg) {
    final editedId = rawMsg['messageId']?.toString();
    final newContent = rawMsg['content']?.toString();
    final editedAt = rawMsg['editedAt']?.toString();
    if (editedId == null || newContent == null) return;
    final updated = getCurrentState().messages.map((m) {
      if (m.effectiveId == editedId || m.messageId == editedId) {
        return m.copyWith(content: newContent, edited: true, editedAt: editedAt);
      }
      return m;
    }).toList();
    setState(getCurrentState().copyWith(messages: updated));
  }

  void _handleReactionUpdated(Map<String, dynamic> rawMsg) {
    final msgId = rawMsg['messageId']?.toString();
    if (msgId == null) return;
    final reactions = ChatMessage.parseReactions(rawMsg['reactions']);
    final updated = getCurrentState().messages.map((m) {
      if (m.effectiveId == msgId || m.messageId == msgId) {
        return m.copyWith(reactions: reactions);
      }
      return m;
    }).toList();
    setState(getCurrentState().copyWith(messages: updated));
  }

  void _handleNewMessage(Map<String, dynamic> rawMsg) {
    final msg = ChatMessage.fromJson(rawMsg);
    final state = getCurrentState();
    final existing = state.messages;

    // Replace local 'sending' message with server-confirmed version
    final sendingIdx = existing.indexWhere((m) =>
        m.deliveryStatus == MessageDeliveryStatus.sending &&
        m.userId == msg.userId &&
        m.content == msg.content);
    if (sendingIdx >= 0) {
      final replaced = List<ChatMessage>.from(existing);
      replaced[sendingIdx] = msg;
      // Cancel the timeout timer for the matched local message
      final localId = existing[sendingIdx].localId;
      onSendingConfirmed(localId);
      setState(state.copyWith(messages: replaced));
      return;
    }

    // Dedup by effectiveId
    if (existing.any((m) => m.effectiveId == msg.effectiveId)) return;

    final updated = [...existing, msg];
    // Cap at 500
    final capped =
        updated.length > 500 ? updated.sublist(updated.length - 500) : updated;
    // New message changes the timeline — recompute readCounts
    setState(state.copyWith(
      messages: capped,
      readCounts: computeReadCounts(capped, state.readPositions),
    ));

    onIncomingChatMessage(msg);
  }
}
