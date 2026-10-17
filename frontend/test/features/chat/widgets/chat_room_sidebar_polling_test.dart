import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/chat_rooms_provider.dart';
import 'package:chatflow/features/chat/widgets/chat_room_sidebar.dart';
import 'package:chatflow/shared/models/chat_room.dart';

class _FakeRooms extends StateNotifier<AsyncValue<List<ChatRoom>>>
    implements ChatRoomsNotifier {
  int fetchRoomsCalls = 0;
  int unreadCalls = 0;
  Map<String, int> unread;

  _FakeRooms(this.unread) : super(const AsyncValue.data([]));

  @override
  Future<void> fetchRooms() async => fetchRoomsCalls++;

  @override
  Future<Map<String, int>> fetchUnreadCounts() async {
    unreadCalls++;
    return unread;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

Widget _app(_FakeRooms fake) {
  return ProviderScope(
    overrides: [
      chatRoomsProvider.overrideWith((_) => fake),
      activeRoomIdProvider.overrideWith((_) => 'a'),
    ],
    child: MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: Column(children: [
            const SizedBox(
                height: 400, child: ChatRoomSidebar(currentRoomId: 'a')),
            TextButton(
              key: const Key('push'),
              onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const Scaffold())),
              child: const Text('push'),
            ),
          ]),
        ),
      ),
    ),
  );
}

void main() {
  setUpAll(() => dotenv.testLoad(fileInput: ''));

  // The fixed-width header overflows under the test font; irrelevant here.
  void ignoreOverflow() {
    final orig = FlutterError.onError;
    FlutterError.onError = (d) {
      if (d.exceptionAsString().contains('overflowed')) return;
      orig?.call(d);
    };
    addTearDown(() => FlutterError.onError = orig);
  }

  testWidgets('active room count stays 0 after initial load and poll',
      (tester) async {
    ignoreOverflow();
    final fake = _FakeRooms({'a': 5, 'b': 3});
    await tester.pumpWidget(_app(fake));
    final container =
        ProviderScope.containerOf(tester.element(find.byType(ChatRoomSidebar)));
    await tester.pump();
    await tester.pump();

    expect(container.read(roomUnreadCountsProvider)['a'], 0);
    expect(container.read(roomUnreadCountsProvider)['b'], 3);

    fake.unread = {'a': 7, 'b': 4};
    await tester.pump(const Duration(seconds: 30));
    await tester.pump();
    expect(container.read(roomUnreadCountsProvider)['a'], 0);
    expect(container.read(roomUnreadCountsProvider)['b'], 4);
  });

  testWidgets('sidebar under a pushed route does not poll', (tester) async {
    ignoreOverflow();
    final fake = _FakeRooms({});
    await tester.pumpWidget(_app(fake));
    await tester.pump();
    final initial = fake.unreadCalls;

    await tester.pump(const Duration(seconds: 30));
    expect(fake.fetchRoomsCalls, 1);

    await tester.tap(find.byKey(const Key('push')));
    await tester.pumpAndSettle();
    final before = fake.fetchRoomsCalls;
    await tester.pump(const Duration(seconds: 30));
    await tester.pump(const Duration(seconds: 30));
    expect(fake.fetchRoomsCalls, before);
    expect(fake.unreadCalls, initial + 1);
  });
}
