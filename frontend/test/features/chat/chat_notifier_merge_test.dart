import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/core/network/stomp_service.dart';
import 'package:chatflow/core/utils/server_time.dart';
import 'package:chatflow/features/chat/chat_notifier.dart';
import 'package:chatflow/shared/models/chat_message.dart';

class _FakeDioClient implements DioClient {
  _FakeDioClient(this.dio);

  @override
  final Dio dio;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeStompService extends StompService {
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
  }) {}
}

final _refProvider = Provider<Ref>((ref) => ref);

Map<String, dynamic> _msg(String id, String timestamp,
        {String type = 'CHAT'}) =>
    {
      'messageId': id,
      'chatRoomId': 'room-1',
      'userId': 'u2',
      'username': 'bob',
      'content': 'content $id',
      'type': type,
      'timestamp': timestamp,
    };

Map<String, dynamic> _ok(Object data) => {'success': true, 'data': data};

/// Routes each path to a body; [onPath] can delay a specific request.
ChatNotifier _notifier(
  ProviderContainer container,
  Map<String, Object> bodies, {
  Future<void> Function(String path)? onPath,
  void Function(RequestOptions options)? onRequest,
}) {
  final dio = Dio(BaseOptions(baseUrl: 'http://test'));
  dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async {
    onRequest?.call(options);
    final body = bodies[options.path];
    if (body == null) {
      handler.reject(DioException(requestOptions: options));
      return;
    }
    if (onPath != null) await onPath(options.path);
    handler.resolve(
        Response(requestOptions: options, statusCode: 200, data: body));
  }));
  final notifier = ChatNotifier(
    _FakeDioClient(dio),
    ref: container.read(_refProvider),
    token: 'tok',
    username: 'alice',
    userId: 'u1',
    stompService: _FakeStompService(),
  );
  addTearDown(notifier.dispose);
  return notifier;
}

/// Latest state, read through a listener (StateNotifier.state is protected).
ChatMessagesState Function() _stateOf(ChatNotifier notifier) {
  late ChatMessagesState latest;
  notifier.addListener((s) => latest = s);
  return () => latest;
}

void main() {
  late ProviderContainer container;
  setUp(() {
    container = ProviderContainer();
    addTearDown(container.dispose);
  });

  test('cursor_ignoresOlderSummaries', () async {
    // 50 history messages from 10:00:00 on, so the room has more history.
    final history = [
      for (var i = 0; i < 50; i++)
        _msg('m$i', '2026-10-16T10:00:${i.toString().padLeft(2, '0')}'),
    ];
    String? before;
    final notifier = _notifier(
      container,
      {
        '/api/chat/rooms/room-1/messages': _ok(history),
        // A summary older than every history message.
        '/api/ai-summary/room/room-1': _ok([
          _msg('s1', '2026-10-16T09:00:00', type: 'AI_SUMMARY'),
        ]),
        '/api/chat/rooms/room-1/messages/cursor':
            _ok({'messages': [], 'hasMore': false}),
      },
      onRequest: (o) {
        if (o.path.endsWith('/cursor')) {
          before = o.queryParameters['before']?.toString();
        }
      },
    );

    final state = _stateOf(notifier);
    await notifier.joinRoom('room-1');
    expect(state().messages.first.messageId, 's1');
    await notifier.loadMoreHistory('room-1');

    expect(parseServerDateTime(before),
        parseServerDateTime('2026-10-16T10:00:00'));
  });

  test('readCounts_recomputedAfterHistoryMerge', () async {
    final readersDone = Completer<void>();
    final notifier = _notifier(
      container,
      {
        // bob (u2) has read up to m2.
        '/api/chat/rooms/room-1/readers': _ok({'u2': 'm2'}),
        '/api/chat/rooms/room-1/messages': _ok([
          _msg('m1', '2026-10-16T10:00:01'),
          _msg('m2', '2026-10-16T10:00:02'),
          _msg('m3', '2026-10-16T10:00:03'),
        ]),
      },
      onPath: (path) async {
        if (path.endsWith('/readers')) {
          scheduleMicrotask(readersDone.complete);
        } else if (path.endsWith('/messages')) {
          // Read positions land before history does.
          await readersDone.future;
          await Future<void>.delayed(Duration.zero);
        }
      },
    );

    final state = _stateOf(notifier);
    await notifier.joinRoom('room-1');

    expect(state().readPositions, {'u2': 'm2'});
    expect(state().readCounts, {'m1': 1, 'm2': 1});
  });

  test('pendingBubble_sortsBetweenOlderAndNewerServerMessages', () async {
    // Server sends naive UTC timestamps.
    String serverTime(Duration offset) => DateTime.now()
        .toUtc()
        .add(offset)
        .toIso8601String()
        .replaceAll('Z', '');
    final notifier = _notifier(container, {
      '/api/chat/rooms/room-1/messages':
          _ok([_msg('old', serverTime(const Duration(minutes: -1)))]),
    });
    final state = _stateOf(notifier);
    await notifier.joinRoom('room-1');

    notifier.sendMessage(roomId: 'room-1', content: 'pending');
    final bubble = state().messages.last;
    expect(
        parseServerDateTime(bubble.timestamp)!
            .difference(DateTime.now())
            .inSeconds
            .abs(),
        lessThan(5));

    notifier.mergeMessage(ChatMessage.fromJson(
        _msg('new', serverTime(const Duration(minutes: 1)))));

    expect(state().messages.map((m) => m.messageId ?? m.content),
        ['old', 'pending', 'new']);
  });
}
