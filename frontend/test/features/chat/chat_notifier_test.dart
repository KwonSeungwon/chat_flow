import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/core/network/stomp_service.dart';
import 'package:chatflow/features/chat/chat_notifier.dart';

class _FakeDioClient implements DioClient {
  _FakeDioClient(this.dio);

  @override
  final Dio dio;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Counts connect() calls; never touches the network.
class _FakeStompService extends StompService {
  int connectCalls = 0;

  @override
  void connect({
    required String roomId,
    required String username,
    required String userId,
    required String token,
    required MessageCallback onMessage,
    required ConnectionCallback onConnectionChanged,
    Future<String?> Function()? tokenProvider,
    ReadReceiptCallback? onReadReceipt,
    TypingCallback? onTyping,
    PresenceCallback? onPresence,
    void Function(String? redirectTo, String? roomName)? onRoomFull,
    MemberListCallback? onMembersUpdate,
    KickedCallback? onKicked,
    MutedCallback? onMuted,
    BannedCallback? onBanned,
    TransientErrorCallback? onTransientError,
    JoinRejectedCallback? onJoinRejected,
  }) {
    connectCalls++;
  }
}

final _refProvider = Provider<Ref>((ref) => ref);

void main() {
  // Each await in joinRoom gets its own case: the notifier is disposed while
  // that request is in flight, and joinRoom must stop before connecting.
  const stages = {
    'history': '/api/chat/rooms/room-1/messages',
    'lastRead': '/api/chat/rooms/room-1/last-read',
  };
  const okBodies = {
    '/api/chat/rooms/room-1/messages': {'success': true, 'data': []},
    '/api/chat/rooms/room-1/last-read': {
      'success': true,
      'data': {'lastReadMessageId': ''},
    },
  };

  for (final MapEntry(key: stage, value: gatedPath) in stages.entries) {
    test('joinRoom_disposedMidway_doesNotConnect ($stage)', () async {
      final gate = Completer<void>();
      final gatedRequested = Completer<void>();
      final dio = Dio(BaseOptions(baseUrl: 'http://test'));
      dio.interceptors
          .add(InterceptorsWrapper(onRequest: (options, handler) async {
        final body = okBodies[options.path];
        if (body == null) {
          handler.reject(DioException(requestOptions: options));
          return;
        }
        if (options.path == gatedPath) {
          gatedRequested.complete();
          await gate.future;
        }
        handler.resolve(
            Response(requestOptions: options, statusCode: 200, data: body));
      }));
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final stomp = _FakeStompService();
      final notifier = ChatNotifier(
        _FakeDioClient(dio),
        ref: container.read(_refProvider),
        token: 'tok',
        username: 'alice',
        userId: 'u1',
        stompService: stomp,
      );

      final join = notifier.joinRoom('room-1');
      await gatedRequested.future;
      notifier.dispose(); // user navigated away mid-join
      gate.complete();
      await join;

      expect(stomp.connectCalls, 0);
    });
  }

  test('joinRoom_completes_connects (control)', () async {
    final dio = Dio(BaseOptions(baseUrl: 'http://test'));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
      final body = okBodies[options.path];
      if (body == null) {
        handler.reject(DioException(requestOptions: options));
      } else {
        handler.resolve(
            Response(requestOptions: options, statusCode: 200, data: body));
      }
    }));
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final stomp = _FakeStompService();
    final notifier = ChatNotifier(
      _FakeDioClient(dio),
      ref: container.read(_refProvider),
      token: 'tok',
      username: 'alice',
      userId: 'u1',
      stompService: stomp,
    );
    addTearDown(notifier.dispose);

    await notifier.joinRoom('room-1');

    expect(stomp.connectCalls, 1);
  });

  for (final op in ['delete', 'edit']) {
    test('${op}Message_failsAfterDispose_noStateWrite', () async {
      final gate = Completer<void>();
      final dio = Dio(BaseOptions(baseUrl: 'http://test'));
      dio.interceptors
          .add(InterceptorsWrapper(onRequest: (options, handler) async {
        await gate.future;
        handler.reject(DioException(requestOptions: options));
      }));
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final notifier = ChatNotifier(
        _FakeDioClient(dio),
        ref: container.read(_refProvider),
        token: 'tok',
        username: 'alice',
        userId: 'u1',
        stompService: _FakeStompService(),
      );

      final result = op == 'delete'
          ? notifier.deleteMessage('room-1', 'm1')
          : notifier.editMessage('room-1', 'm1', 'new');
      notifier.dispose(); // user left the room while the request was in flight
      gate.complete();

      expect(await result, isFalse);
    });
  }
}
