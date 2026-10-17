import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/core/network/stomp_service.dart';
import 'package:chatflow/core/utils/server_time.dart';
import 'package:chatflow/features/chat/chat_notifier.dart';
import 'package:chatflow/features/chat/chat_rooms_provider.dart';
import 'package:chatflow/shared/models/chat_message.dart';

class _FakeDioClient implements DioClient {
  _FakeDioClient(this.dio);

  @override
  final Dio dio;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeStompService extends StompService {
  ConnectionCallback? onConnectionChanged;
  int reconnectNowCalls = 0;

  @override
  void reconnectNow() => reconnectNowCalls++;

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
    this.onConnectionChanged = onConnectionChanged;
  }
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
  _FakeStompService? stomp,
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
    stompService: stomp ?? _FakeStompService(),
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

  test('cursor_ignoresOlderMergedMessages', () async {
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
    // A message older than every history message (e.g. a fetched thread reply).
    notifier.mergeMessage(ChatMessage.fromJson(_msg('s1', '2026-10-16T09:00:00')));
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

  test('reconnect_refetchesLatestHistoryAndMerges', () async {
    final stomp = _FakeStompService();
    var page = [_msg('m1', '2026-10-16T10:00:01')];
    var historyCalls = 0;
    final bodies = <String, Object>{};
    final notifier = _notifier(container, bodies, stomp: stomp,
        onRequest: (o) {
      if (o.path == '/api/chat/rooms/room-1/messages') {
        historyCalls++;
        bodies[o.path] = _ok(page);
      }
    });
    final state = _stateOf(notifier);
    await notifier.joinRoom('room-1');

    stomp.onConnectionChanged!(true); // first connect: no refetch
    await Future<void>.delayed(Duration.zero);
    expect(historyCalls, 1);

    // Socket drops; m2 is sent while we are away; socket comes back.
    stomp.onConnectionChanged!(false);
    page = [
      _msg('m1', '2026-10-16T10:00:01'),
      _msg('m2', '2026-10-16T10:00:02'),
    ];
    stomp.onConnectionChanged!(true);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(historyCalls, 2);
    expect(state().messages.map((m) => m.messageId), ['m1', 'm2']);
  });

  test('reconnectIfNeeded_delegatesToStomp', () {
    final stomp = _FakeStompService();
    final notifier = _notifier(container, const {}, stomp: stomp);
    notifier.reconnectIfNeeded();
    expect(stomp.reconnectNowCalls, 1);
  });

  test('activeRoom_noUnreadBadge_andMarksLastHistoryMessageRead', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    FlutterSecureStorage.setMockInitialValues({});
    dotenv.testLoad(fileInput: '');
    container.read(activeRoomIdProvider.notifier).state = 'room-1';
    final puts = <Object?>[];
    final notifier = _notifier(
      container,
      {
        '/api/chat/rooms/room-1/messages': _ok([
          _msg('m1', '2026-10-16T10:00:01'),
          _msg('m2', '2026-10-16T10:00:02'),
        ]),
        '/api/chat/rooms/room-1/last-read': _ok({'lastReadMessageId': 'm1'}),
      },
      onRequest: (o) {
        if (o.method == 'PUT' && o.path.endsWith('/last-read')) {
          puts.add((o.data as Map)['lastReadMessageId']);
        }
      },
    );

    await notifier.joinRoom('room-1');
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(container.read(roomUnreadCountsProvider)['room-1'] ?? 0, 0);
    expect(puts, ['m2']);
  });

  test('inactiveRoom_keepsUnreadCount', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    FlutterSecureStorage.setMockInitialValues({});
    dotenv.testLoad(fileInput: '');
    final notifier = _notifier(container, {
      '/api/chat/rooms/room-1/messages': _ok([
        _msg('m1', '2026-10-16T10:00:01'),
        _msg('m2', '2026-10-16T10:00:02'),
      ]),
      '/api/chat/rooms/room-1/last-read': _ok({'lastReadMessageId': 'm1'}),
    });

    await notifier.joinRoom('room-1');

    expect(container.read(roomUnreadCountsProvider)['room-1'], 1);
  });

  test('reconnect_refetchSettlesOwnPendingBubble_andMarksRead', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    FlutterSecureStorage.setMockInitialValues({});
    dotenv.testLoad(fileInput: '');
    container.read(activeRoomIdProvider.notifier).state = 'room-1';
    final stomp = _FakeStompService();
    var page = <Map<String, dynamic>>[_msg('m1', '2026-10-16T10:00:01')];
    final bodies = <String, Object>{
      '/api/chat/rooms/room-1/last-read': _ok({'lastReadMessageId': ''}),
    };
    final puts = <Object?>[];
    final notifier = _notifier(container, bodies, stomp: stomp, onRequest: (o) {
      if (o.path == '/api/chat/rooms/room-1/messages') bodies[o.path] = _ok(page);
      if (o.method == 'PUT' && o.path.endsWith('/last-read')) {
        puts.add((o.data as Map)['lastReadMessageId']);
      }
    });
    final state = _stateOf(notifier);
    await notifier.joinRoom('room-1');
    stomp.onConnectionChanged!(true);
    stomp.onConnectionChanged!(false);

    // Sent while the socket is down: an optimistic bubble.
    notifier.sendMessage(roomId: 'room-1', content: 'hi');
    final localId = state().messages.last.localId!;
    await Future<void>.delayed(const Duration(milliseconds: 10));
    puts.clear();

    // The server got it (e.g. flushed just before the drop); its copy comes
    // back in the refetch, the echo never arrived.
    page = [
      _msg('m1', '2026-10-16T10:00:01'),
      {
        ..._msg('m2', '2026-10-16T10:00:02'),
        'userId': 'u1',
        'username': 'alice',
        'content': 'hi',
        'clientMessageId': localId,
      },
    ];
    stomp.onConnectionChanged!(true);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    final msgs = state().messages;
    expect(msgs.map((m) => m.messageId), ['m1', 'm2']);
    expect(msgs.last.deliveryStatus, MessageDeliveryStatus.sent);
    expect(puts, ['m2']);
  });

  group('reconnect refetch gap fill', () {
    List<Map<String, dynamic>> pageOf(String prefix, int minute) => [
          for (var i = 0; i < 50; i++)
            _msg('$prefix$i',
                '2026-10-16T10:${minute.toString().padLeft(2, '0')}:${i.toString().padLeft(2, '0')}'),
        ];

    Future<(ChatNotifier, _FakeStompService, List<Object?>)> joined(
        List<Map<String, dynamic>> Function(int page) pageBody) async {
      final stomp = _FakeStompService();
      final pages = <Object?>[];
      var joinedRoom = false;
      final bodies = <String, Object>{};
      final notifier = _notifier(container, bodies, stomp: stomp,
          onRequest: (o) {
        if (o.path != '/api/chat/rooms/room-1/messages') return;
        if (!joinedRoom) {
          bodies[o.path] = _ok([_msg('m1', '2026-10-16T09:00:00')]);
          return;
        }
        final page = (o.queryParameters['page'] as int?) ?? 0;
        pages.add(page);
        bodies[o.path] = _ok(pageBody(page));
      });
      await notifier.joinRoom('room-1');
      joinedRoom = true;
      stomp.onConnectionChanged!(true);
      stomp.onConnectionChanged!(false);
      return (notifier, stomp, pages);
    }

    test('a gap over 50 pages back until it overlaps', () async {
      final (notifier, stomp, pages) = await joined((page) => switch (page) {
            0 => pageOf('b', 2),
            _ => [_msg('m1', '2026-10-16T09:00:00'), ...pageOf('a', 1).take(49)],
          });
      final state = _stateOf(notifier);
      stomp.onConnectionChanged!(true);
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(pages, [0, 1]);
      final ids = state().messages.map((m) => m.messageId).toList();
      expect(ids.length, 1 + 49 + 50);
      expect(ids.first, 'm1');
      expect(ids.last, 'b49');
    });

    test('gap fill stops after 5 more pages', () async {
      final (_, stomp, pages) =
          await joined((page) => pageOf('p$page-', 30 - page));
      stomp.onConnectionChanged!(true);
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(pages, [0, 1, 2, 3, 4, 5]);
    });

    test('refetch updates edited content and deleted state of known messages',
        () async {
      final (notifier, stomp, _) = await joined((_) => [
            {
              ..._msg('m1', '2026-10-16T09:00:00'),
              'content': 'fixed',
              'edited': true,
              'editedAt': '2026-10-16T09:05:00',
            },
          ]);
      final state = _stateOf(notifier);
      stomp.onConnectionChanged!(true);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      var m1 = state().messages.single;
      expect(m1.content, 'fixed');
      expect(m1.edited, isTrue);
      expect(m1.editedAt, '2026-10-16T09:05:00');

      stomp.onConnectionChanged!(false);
      // Deleted while away.
      final (notifier2, stomp2, _) = await joined((_) => [
            {
              ..._msg('m1', '2026-10-16T09:00:00'),
              'content': '삭제된 메시지입니다.',
              'deleted': true,
            },
          ]);
      final state2 = _stateOf(notifier2);
      stomp2.onConnectionChanged!(true);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      m1 = state2().messages.single;
      expect(m1.deleted, isTrue);
      expect(m1.content, '삭제된 메시지입니다.');
    });
  });
}
