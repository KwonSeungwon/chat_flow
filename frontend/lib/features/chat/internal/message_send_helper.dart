import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:uuid/uuid.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/stomp_service.dart';
import '../../../shared/models/chat_message.dart';
import '../../../shared/models/patient_card.dart';
import '../helpers/offline_message_queue.dart';
import '../state/chat_messages_state.dart';

/// Outbound message actions (send, retry, send patient card, upload file,
/// forward). Extracted from ChatNotifier as part of Stage 2.5 to bring
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
    String priority = 'ROUTINE',
    ChatMessage? replyOverride,
  }) {
    // replyOverride lets ThreadPanel post replies without mutating
    // state.replyTarget (which is owned by the main chat input).
    final reply = replyOverride ?? getCurrentState().replyTarget;
    final localId = const Uuid().v4();
    final msg = {
      'chatRoomId': roomId,
      'userId': userId,
      'username': username,
      'content': content,
      'type': 'CHAT',
      'priority': priority,
      'timestamp': DateTime.now().toIso8601String(),
      if (reply != null) 'parentMessageId': reply.effectiveId,
      '_localId': localId,
    };
    // Show local message immediately with 'sending' status
    final localMsg = ChatMessage(
      chatRoomId: roomId, userId: userId, username: username,
      content: content, type: 'CHAT', priority: priority,
      timestamp: msg['timestamp']!,
      parentMessageId: reply?.effectiveId,
      localId: localId,
      deliveryStatus: MessageDeliveryStatus.sending,
    );
    setState(getCurrentState().copyWith(messages: [...getCurrentState().messages, localMsg]));
    if (stompService.isConnected) {
      final sendPayload = Map<String, dynamic>.from(msg)..remove('_localId');
      stompService.sendMessage(sendPayload);
    } else {
      offlineQueue.enqueue(msg);
    }
    // Only clear when the reply came from state (main chat input).
    // Override callers (ThreadPanel) manage their own state.
    if (reply != null && replyOverride == null) clearReplyTarget();
    // 10초 내 서버 확인(동일 localId 메시지가 sent로 교체) 없으면 failed로 표시
    sendingTimers[localId] = Timer(const Duration(seconds: 10), () {
      sendingTimers.remove(localId);
      if (!mounted()) return;
      final idx = getCurrentState().messages.indexWhere((m) =>
          m.localId == localId && m.deliveryStatus == MessageDeliveryStatus.sending);
      if (idx < 0) return;
      final m = getCurrentState().messages[idx];
      final failed = m.copyWith(deliveryStatus: MessageDeliveryStatus.failed);
      final list = List<ChatMessage>.from(getCurrentState().messages);
      list[idx] = failed;
      setState(getCurrentState().copyWith(messages: list));
    });
  }

  /// 실패한 메시지 재전송. 기존 localMsg를 제거하고 sendMessage 재호출.
  void retryFailedMessage(ChatMessage msg) {
    if (msg.deliveryStatus != MessageDeliveryStatus.failed) return;
    final list = getCurrentState().messages.where((m) => m.localId != msg.localId).toList();
    setState(getCurrentState().copyWith(messages: list));
    // Preserve thread association on retry — without replyOverride, a failed
    // reply would silently re-post as a top-level message.
    ChatMessage? parent;
    if (msg.parentMessageId != null) {
      parent = getCurrentState().messages
          .cast<ChatMessage?>()
          .firstWhere(
              (m) => m?.effectiveId == msg.parentMessageId,
              orElse: () => null);
      // Parent evicted from the 500-message buffer (or deleted) — build a
      // minimal stub so the wire-format still carries parentMessageId.
      // The backend computes parentMessagePreview from the parent's stored
      // row, so a stub with only messageId set is sufficient.
      parent ??= ChatMessage(
        chatRoomId: msg.chatRoomId,
        userId: '',
        username: '',
        content: '',
        type: 'CHAT',
        priority: 'ROUTINE',
        timestamp: msg.timestamp,
        messageId: msg.parentMessageId,
      );
    }
    sendMessage(
      roomId: msg.chatRoomId,
      content: msg.content,
      priority: msg.priority,
      replyOverride: parent,
    );
  }

  void sendPatientCard(String roomId, PatientCard card) {
    stompService.sendMessage({
      'chatRoomId': roomId,
      'userId': userId,
      'username': username,
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
    final result = await dioClient.uploadFile(
      fileName: fileName,
      bytes: bytes,
      mimeType: mimeType,
    );
    final fileUrl = result['fileUrl']?.toString() ?? '';
    final storedName = result['fileName']?.toString() ?? fileName;
    final contentType = result['fileContentType']?.toString() ?? mimeType;

    final msgContent = content.isNotEmpty ? content : '[파일] $storedName';

    stompService.sendMessage({
      'chatRoomId': roomId,
      'userId': userId,
      'username': username,
      'content': msgContent,
      'type': 'FILE',
      'fileUrl': fileUrl,
      'fileName': storedName,
      'fileContentType': contentType,
      'priority': 'ROUTINE',
      'timestamp': DateTime.now().toIso8601String(),
    });
  }

  Future<bool> forwardMessage(String targetRoomId, ChatMessage msg) async {
    final isFile = msg.isFileMessage;
    final content = '[전달] ${msg.username}: ${msg.content}';
    final forwardedFrom = '${msg.username}: ${msg.content.length > 100 ? '${msg.content.substring(0, 100)}...' : msg.content}';

    if (stompService.isConnected) {
      stompService.sendMessage({
        'chatRoomId': targetRoomId,
        'userId': userId,
        'username': username,
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
