import 'dart:async';
import 'dart:typed_data';
import 'package:uuid/uuid.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/stomp_service.dart';
import '../../../shared/models/chat_message.dart';
import '../helpers/offline_message_queue.dart';
import '../state/chat_messages_state.dart';

/// Outbound message actions (send, retry, upload file, forward).
/// Extracted from ChatNotifier as part of Stage 2.5 to bring
/// chat_notifier.dart back within the 700-LOC budget.
///
/// Pure delegation via closures — does not inherit from or depend on
/// StateNotifier semantics. Lifecycle ownership stays in ChatNotifier.
class MessageSendHelper {
  final ChatMessagesState Function() getCurrentState;
  final void Function(ChatMessagesState) setState;
  final bool Function() mounted;
  final String userId;
  final String username;
  final StompService stompService;
  final OfflineMessageQueue offlineQueue;
  final Map<String, Timer> sendingTimers;
  final DioClient dioClient;

  /// Called after sending a state-owned reply so the input clears.
  /// (ThreadPanel passes replyOverride to bypass this.)
  final void Function() clearReplyTarget;

  MessageSendHelper({
    required this.getCurrentState,
    required this.setState,
    required this.mounted,
    required this.userId,
    required this.username,
    required this.stompService,
    required this.offlineQueue,
    required this.sendingTimers,
    required this.dioClient,
    required this.clearReplyTarget,
  });

  void sendMessage({
    required String roomId,
    required String content,
    ChatMessage? replyOverride,
  }) {
    // replyOverride lets ThreadPanel post replies without mutating
    // state.replyTarget (which is owned by the main chat input).
    final reply = replyOverride ?? getCurrentState().replyTarget;
    sendTyped(
      roomId: roomId,
      content: content,
      type: 'CHAT',
      parentMessageId: reply?.effectiveId,
    );
    // Only clear when the reply came from state (main chat input).
    // Override callers (ThreadPanel) manage their own state.
    if (reply != null && replyOverride == null) clearReplyTarget();
  }

  /// Optimistic send for every user-sendable type (CHAT, FILE).
  /// Shows a 'sending' bubble, then sends over STOMP or queues it for the
  /// reconnect flush. The bubble's localId travels as `clientMessageId` so
  /// the server echo replaces exactly this bubble. A retry passes the
  /// original [localId] so every echo of either attempt maps to one bubble.
  void sendTyped({
    required String roomId,
    required String content,
    required String type,
    String? parentMessageId,
    String? fileUrl,
    String? fileName,
    String? fileContentType,
    String? localId,
  }) {
    localId ??= const Uuid().v4();
    // UTC with offset: naive strings are parsed as UTC when sorting.
    final timestamp = DateTime.now().toUtc().toIso8601String();
    final msg = <String, dynamic>{
      'chatRoomId': roomId,
      'userId': userId,
      'username': username,
      'content': content,
      'type': type,
      'timestamp': timestamp,
      if (parentMessageId != null) 'parentMessageId': parentMessageId,
      if (fileUrl != null) 'fileUrl': fileUrl,
      if (fileName != null) 'fileName': fileName,
      if (fileContentType != null) 'fileContentType': fileContentType,
      'clientMessageId': localId,
      '_localId': localId,
    };
    final localMsg = ChatMessage(
      chatRoomId: roomId,
      userId: userId,
      username: username,
      content: content,
      type: type,
      timestamp: timestamp,
      parentMessageId: parentMessageId,
      fileUrl: fileUrl,
      fileName: fileName,
      fileContentType: fileContentType,
      localId: localId,
      deliveryStatus: MessageDeliveryStatus.sending,
    );
    setState(getCurrentState()
        .copyWith(messages: [...getCurrentState().messages, localMsg]));
    if (stompService.isConnected) {
      _sendOrQueue(msg);
    } else {
      offlineQueue.enqueue(msg);
    }
    _armSendingTimeout(localId);
  }

  /// Sends a queued payload; if the socket died between the isConnected
  /// check and the write (StompBadStateException), queues it for the
  /// reconnect flush.
  void _sendOrQueue(Map<String, dynamic> msg) {
    try {
      // _localId is a client-only marker; strip it from the wire payload.
      stompService
          .sendMessage(Map<String, dynamic>.from(msg)..remove('_localId'));
    } catch (_) {
      offlineQueue.enqueue(msg);
    }
  }

  /// 10초 내 서버 확인(동일 clientMessageId 에코가 sent로 교체) 없으면 failed로 표시
  void _armSendingTimeout(String localId) {
    sendingTimers.remove(localId)?.cancel();
    sendingTimers[localId] = Timer(const Duration(seconds: 10), () {
      sendingTimers.remove(localId);
      if (!mounted()) return;
      final idx = getCurrentState().messages.indexWhere((m) =>
          m.localId == localId &&
          m.deliveryStatus == MessageDeliveryStatus.sending);
      if (idx < 0) return;
      final m = getCurrentState().messages[idx];
      final failed = m.copyWith(deliveryStatus: MessageDeliveryStatus.failed);
      final list = List<ChatMessage>.from(getCurrentState().messages);
      list[idx] = failed;
      setState(getCurrentState().copyWith(messages: list));
    });
  }

  /// Called on (re)connect. Queued bubbles stay visible: each goes back to
  /// 'sending' with a fresh timeout, and its echo replaces it by
  /// clientMessageId.
  void flushOfflineQueue() {
    offlineQueue.flush(
      onDedup: (ids) {
        if (!mounted()) return;
        setState(getCurrentState().copyWith(messages: [
          for (final m in getCurrentState().messages)
            ids.contains(m.localId)
                ? m.copyWith(deliveryStatus: MessageDeliveryStatus.sending)
                : m,
        ]));
        ids.forEach(_armSendingTimeout);
      },
      onSend: _sendOrQueue,
    );
  }

  /// 실패한 메시지 재전송. 대기열의 원본과 기존 버블을 지우고 같은 내용으로 다시 보낸다.
  void retryFailedMessage(ChatMessage msg) {
    if (msg.deliveryStatus != MessageDeliveryStatus.failed) return;
    final localId = msg.localId;
    if (localId != null) {
      // Still queued from an offline send — drop it, or the reconnect
      // flush would deliver it alongside the retry.
      offlineQueue.removeByLocalId(localId);
      sendingTimers.remove(localId)?.cancel();
    }
    final list =
        getCurrentState().messages.where((m) => m.localId != localId).toList();
    setState(getCurrentState().copyWith(messages: list));
    // parentMessageId is resent as-is: the backend builds the preview from
    // the parent's stored row, so the parent need not be in the buffer.
    sendTyped(
      roomId: msg.chatRoomId,
      content: msg.content,
      type: msg.type,
      parentMessageId: msg.parentMessageId,
      fileUrl: msg.fileUrl,
      fileName: msg.fileName,
      fileContentType: msg.fileContentType,
      localId: localId,
    );
  }

  Future<void> uploadAndSendFile({
    required String roomId,
    required String fileName,
    required Uint8List bytes,
    required String mimeType,
    String content = '',
  }) async {
    final result = await dioClient.uploadFile(
      fileName: fileName,
      bytes: bytes,
      mimeType: mimeType,
    );
    if (!mounted()) return;
    final fileUrl = result['fileUrl']?.toString() ?? '';
    final storedName = result['fileName']?.toString() ?? fileName;
    final contentType = result['fileContentType']?.toString() ?? mimeType;

    final msgContent = content.isNotEmpty ? content : '[파일] $storedName';

    sendTyped(
      roomId: roomId,
      content: msgContent,
      type: 'FILE',
      fileUrl: fileUrl,
      fileName: storedName,
      fileContentType: contentType,
    );
  }

  Future<bool> forwardMessage(String targetRoomId, ChatMessage msg) async {
    final isFile = msg.isFileMessage;
    final content = '[전달] ${msg.username}: ${msg.content}';
    final forwardedFrom =
        '${msg.username}: ${msg.content.length > 100 ? '${msg.content.substring(0, 100)}...' : msg.content}';

    if (stompService.isConnected) {
      stompService.sendMessage({
        'chatRoomId': targetRoomId,
        'userId': userId,
        'username': username,
        'content': content,
        'type': isFile ? 'FILE' : 'CHAT',
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
      await dioClient.dio.post(
        '/api/chat/rooms/$targetRoomId/messages',
        data: {'content': content, 'forwardedFrom': forwardedFrom},
      );
      return true;
    } catch (_) {
      return false;
    }
  }
}
