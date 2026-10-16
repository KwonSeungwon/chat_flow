import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/core/network/stomp_service.dart';
import 'package:chatflow/features/chat/helpers/offline_message_queue.dart';
import 'package:chatflow/features/chat/internal/message_send_helper.dart';
import 'package:chatflow/features/chat/internal/stomp_message_dispatcher.dart';
import 'package:chatflow/features/chat/state/chat_messages_state.dart';
import 'package:chatflow/shared/models/chat_message.dart';
import 'package:chatflow/shared/models/patient_card.dart';

class _FakeStomp extends StompService {
  bool connected = false;
  final sent = <Map<String, dynamic>>[];

  @override
  bool get isConnected => connected;

  @override
  void sendMessage(Map<String, dynamic> message) => sent.add(message);
}

class _FakeDioClient implements DioClient {
  @override
  Future<Map<String, dynamic>> uploadFile({
    required String fileName,
    required Uint8List bytes,
    required String mimeType,
    void Function(int sent, int total)? onProgress,
  }) async =>
      {
        'fileUrl': '/files/abc.png',
        'fileName': 'abc.png',
        'fileContentType': 'image/png',
      };

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Harness {
  ChatMessagesState state = const ChatMessagesState();
  final stomp = _FakeStomp();
  final queue = OfflineMessageQueue();
  final timers = <String, Timer>{};

  late final helper = MessageSendHelper(
    getCurrentState: () => state,
    setState: (s) => state = s,
    mounted: () => true,
    userId: 'u1',
    username: 'alice',
    stompService: stomp,
    offlineQueue: queue,
    sendingTimers: timers,
    dioClient: _FakeDioClient(),
    clearReplyTarget: () {},
  );

  late final dispatcher = StompMessageDispatcher(
    getCurrentState: () => state,
    setState: (s) => state = s,
    mounted: () => true,
    userId: 'u1',
    currentRoomId: () => 'room-1',
    computeReadCounts: (_, __) => const {},
    onIncomingChatMessage: (_) {},
    onPinChanged: () {},
    onSendingConfirmed: (localId) {
      if (localId != null) timers.remove(localId)?.cancel();
    },
  );

  /// Simulates the 10-second sending timeout firing.
  void failAllSending() {
    state = state.copyWith(
      messages: [
        for (final m in state.messages)
          m.deliveryStatus == MessageDeliveryStatus.sending
              ? m.copyWith(deliveryStatus: MessageDeliveryStatus.failed)
              : m,
      ],
    );
  }

  void dispose() {
    for (final t in timers.values) {
      t.cancel();
    }
  }
}

void main() {
  late _Harness h;
  setUp(() => h = _Harness());
  tearDown(() => h.dispose());

  test('retryWhileOffline_queuesOnce', () {
    h.helper.sendMessage(roomId: 'room-1', content: 'hi');
    h.failAllSending();

    h.helper.retryFailedMessage(h.state.messages.single);

    expect(h.queue.length, 1, reason: 'the queued original must be dropped');
    expect(h.state.messages, hasLength(1));
    expect(h.state.messages.single.deliveryStatus,
        MessageDeliveryStatus.sending);
  });

  test('lateEcho_matchesFailedBubbleByClientId', () {
    h.stomp.connected = true;
    h.helper.sendMessage(roomId: 'room-1', content: 'hi');
    final localId = h.state.messages.single.localId;
    expect(h.stomp.sent.single['clientMessageId'], localId);
    h.failAllSending();

    // Server may normalize content, so matching must not depend on it.
    h.dispatcher.dispatch({
      'messageId': 'm1',
      'chatRoomId': 'room-1',
      'userId': 'u1',
      'username': 'alice',
      'content': 'hi (normalized)',
      'type': 'CHAT',
      'timestamp': '2026-10-16T10:00:00',
      'clientMessageId': localId,
    });

    expect(h.state.messages, hasLength(1));
    expect(h.state.messages.single.messageId, 'm1');
    expect(h.state.messages.single.deliveryStatus, MessageDeliveryStatus.sent);
  });

  test('fileSendWhileOffline_queued', () async {
    await h.helper.uploadAndSendFile(
      roomId: 'room-1',
      fileName: 'abc.png',
      bytes: Uint8List(1),
      mimeType: 'image/png',
    );

    expect(h.stomp.sent, isEmpty);
    expect(h.queue.length, 1);
    final bubble = h.state.messages.single;
    expect(bubble.type, 'FILE');
    expect(bubble.fileUrl, '/files/abc.png');
    expect(bubble.deliveryStatus, MessageDeliveryStatus.sending);

    h.stomp.connected = true;
    h.helper.flushOfflineQueue();
    final payload = h.stomp.sent.single;
    expect(payload['type'], 'FILE');
    expect(payload['fileUrl'], '/files/abc.png');
    expect(payload['clientMessageId'], bubble.localId);
    expect(payload.containsKey('_localId'), isFalse);
    expect(h.state.messages, hasLength(1),
        reason: 'the bubble stays until its echo replaces it');
  });

  test('patientCardWhileOffline_queued', () {
    h.helper.sendPatientCard(
      'room-1',
      const PatientCard(patientName: 'P', roomNumber: '101', diagnosis: 'D'),
    );

    expect(h.stomp.sent, isEmpty);
    expect(h.queue.length, 1);
    expect(h.state.messages.single.type, 'PATIENT_CARD');
  });
}
