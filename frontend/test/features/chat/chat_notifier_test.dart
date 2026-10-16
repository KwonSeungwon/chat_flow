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
  test('joinRoom_disposedMidway_doesNotConnect', () async {
    final historyGate = Completer<void>();
    final historyRequested = Completer<void>();
    final dio = Dio(BaseOptions(baseUrl: 'http://test'));
    dio.interceptors
        .add(InterceptorsWrapper(onRequest: (options, handler) async {
      if (options.path.endsWith('/messages')) {
        historyRequested.complete();
        await historyGate.future;
        handler.resolve(Response(
          requestOptions: options,
          statusCode: 200,
          data: {'success': true, 'data': []},
        ));
      } else {
        handler.reject(DioException(requestOptions: options));
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

    final join = notifier.joinRoom('room-1');
    await historyRequested.future;
    notifier.dispose(); // user navigated away while history was loading
    historyGate.complete();
    await join;

    expect(stomp.connectCalls, 0);
  });
}
