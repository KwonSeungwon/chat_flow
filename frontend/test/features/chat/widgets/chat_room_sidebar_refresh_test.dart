import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/chat_rooms_provider.dart';
import 'package:chatflow/shared/models/chat_room.dart';

// ---------------------------------------------------------------------------
// A mock notifier that tracks fetchRooms() invocations
// ---------------------------------------------------------------------------

class _MockChatRoomsNotifier extends StateNotifier<AsyncValue<List<ChatRoom>>>
    implements ChatRoomsNotifier {
  int fetchCallCount = 0;

  _MockChatRoomsNotifier(List<ChatRoom> rooms)
      : super(AsyncValue.data(rooms));

  @override
  Future<void> fetchRooms() async {
    fetchCallCount++;
    // Simulate brief network delay
    await Future.delayed(const Duration(milliseconds: 50));
  }

  @override
  Future<bool> deleteRoom(String id) async => true;

  @override
  Future<HideRoomResult> hideRoom(String id) async => HideRoomResult.success;

  @override
  Future<Map<String, int>> fetchUnreadCounts() async => {};

  @override
  void updateParticipantCount(String roomId, int count) {}

  @override
  Future<String?> createRoom({
    required String name,
    String? description,
    String? color,
    String roomType = 'GENERAL',
    bool isPrivate = false,
    String? password,
    String? allowedRoles,
  }) async => null;
}

// ---------------------------------------------------------------------------
// Minimal widget that replicates the sidebar's RefreshIndicator pattern
// with a real ListView + provider invalidation, so we can verify the pull-
// to-refresh contract without instantiating the full ChatRoomSidebar (which
// brings in AppStompService, FcmService, FlutterSecureStorage, etc.).
// ---------------------------------------------------------------------------

class _RefreshableRoomList extends ConsumerWidget {
  const _RefreshableRoomList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final roomsAsync = ref.watch(chatRoomsProvider);
    return Scaffold(
      body: roomsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, __) => const Center(child: Text('Error')),
        data: (rooms) {
          if (rooms.isEmpty) {
            return RefreshIndicator(
              onRefresh: () =>
                  ref.read(chatRoomsProvider.notifier).fetchRooms(),
              child: LayoutBuilder(
                builder: (context, constraints) => SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  child: ConstrainedBox(
                    constraints:
                        BoxConstraints(minHeight: constraints.maxHeight),
                    child: const Center(child: Text('No rooms')),
                  ),
                ),
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () =>
                ref.read(chatRoomsProvider.notifier).fetchRooms(),
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              itemCount: rooms.length,
              itemBuilder: (_, i) =>
                  ListTile(title: Text(rooms[i].name)),
            ),
          );
        },
      ),
    );
  }
}

void main() {
  group('Sidebar pull-to-refresh pattern', () {
    testWidgets(
        'RefreshIndicator is present when rooms list is non-empty',
        (tester) async {
      final rooms = [
        ChatRoom(id: '1', name: 'General', participantCount: 3, maxParticipants: 10),
        ChatRoom(id: '2', name: 'Random', participantCount: 1, maxParticipants: 10),
      ];
      final notifier = _MockChatRoomsNotifier(rooms);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            chatRoomsProvider.overrideWith((_) => notifier),
          ],
          child: const MaterialApp(home: _RefreshableRoomList()),
        ),
      );
      await tester.pump();

      expect(find.byType(RefreshIndicator), findsOneWidget);
      expect(find.text('General'), findsOneWidget);
      expect(find.text('Random'), findsOneWidget);
    });

    testWidgets(
        'RefreshIndicator is present when rooms list is empty',
        (tester) async {
      final notifier = _MockChatRoomsNotifier([]);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            chatRoomsProvider.overrideWith((_) => notifier),
          ],
          child: const MaterialApp(home: _RefreshableRoomList()),
        ),
      );
      await tester.pump();

      expect(find.byType(RefreshIndicator), findsOneWidget);
      expect(find.text('No rooms'), findsOneWidget);
    });

    testWidgets('pull-to-refresh triggers fetchRooms', (tester) async {
      final rooms = [
        ChatRoom(id: '1', name: 'Room A', participantCount: 2, maxParticipants: 10),
      ];
      final notifier = _MockChatRoomsNotifier(rooms);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            chatRoomsProvider.overrideWith((_) => notifier),
          ],
          child: const MaterialApp(home: _RefreshableRoomList()),
        ),
      );
      await tester.pump();

      expect(notifier.fetchCallCount, 0);

      // Perform a fling-down gesture to trigger RefreshIndicator
      await tester.fling(
        find.byType(ListView),
        const Offset(0, 300),
        1000,
      );
      await tester.pumpAndSettle();

      expect(notifier.fetchCallCount, greaterThanOrEqualTo(1));
    });

    testWidgets('pull-to-refresh on empty list triggers fetchRooms',
        (tester) async {
      final notifier = _MockChatRoomsNotifier([]);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            chatRoomsProvider.overrideWith((_) => notifier),
          ],
          child: const MaterialApp(home: _RefreshableRoomList()),
        ),
      );
      await tester.pump();

      expect(notifier.fetchCallCount, 0);

      // Perform a fling-down gesture on the scrollable area
      await tester.fling(
        find.byType(SingleChildScrollView),
        const Offset(0, 300),
        1000,
      );
      await tester.pumpAndSettle();

      expect(notifier.fetchCallCount, greaterThanOrEqualTo(1));
    });
  });
}
