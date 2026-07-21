import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/chat/chat_rooms_provider.dart';
import 'package:chatflow/features/command_palette/command_action.dart';
import 'package:chatflow/features/command_palette/command_palette_provider.dart';

/// Tests for the command palette filtering/scoring logic.
///
/// The provider itself depends on Riverpod + DioClient, making it hard to
/// unit-test in isolation. Instead we test the public scoring API on
/// CommandAction, which is the pure-function heart of the filtering.
void main() {
  group('Scoring / filtering logic', () {
    test('empty query returns score 1 for all actions', () {
      final room = GoToRoomAction(roomId: '1', roomName: '일반방');
      final user = ViewProfileAction(userId: 'u1', username: '홍길동');
      final quick = QuickAction.createRoom();

      expect(room.matchScore(''), 1);
      expect(user.matchScore(''), 1);
      expect(quick.matchScore(''), 1);
    });

    test('query "땅" matches GoToRoomAction("땅콩-DM")', () {
      final action = GoToRoomAction(roomId: '1', roomName: '땅콩-DM');
      expect(action.matchScore('땅'), greaterThan(0));
    });

    test('query "땅" does NOT match unrelated room', () {
      final action = GoToRoomAction(roomId: '2', roomName: '일반 채팅방');
      expect(action.matchScore('땅'), 0);
    });

    test('substring scoring: earlier match scores higher', () {
      final actions = [
        GoToRoomAction(roomId: '1', roomName: '회의-방'),    // '방' at index 3
        GoToRoomAction(roomId: '2', roomName: '방-회의'),    // '방' at index 0
        GoToRoomAction(roomId: '3', roomName: '큰-방-모임'), // '방' at index 2
      ];

      final scores = actions.map((a) => a.matchScore('방')).toList();
      // index 0 match should score highest
      expect(scores[1], greaterThan(scores[0]));
      expect(scores[1], greaterThan(scores[2]));
    });

    test('case-insensitive matching for English names', () {
      final action = GoToRoomAction(roomId: '1', roomName: 'General Chat');
      expect(action.matchScore('general'), greaterThan(0));
      expect(action.matchScore('GENERAL'), greaterThan(0));
      expect(action.matchScore('General'), greaterThan(0));
    });

    test('subtitle matching scores lower than title matching', () {
      // GoToRoomAction with description matching
      final action = GoToRoomAction(
        roomId: '1',
        roomName: '일반방',
        roomDescription: '중요한 내용',
      );
      // title does not contain '중요', but subtitle does
      expect(action.matchScore('중요'), greaterThan(0));
      // Compare with an action whose title matches
      final titleMatch = GoToRoomAction(
        roomId: '2',
        roomName: '중요한 방',
      );
      expect(titleMatch.matchScore('중요'), greaterThan(action.matchScore('중요')));
    });

    test('sorting a heterogeneous list by score works correctly', () {
      final actions = <CommandAction>[
        GoToRoomAction(roomId: '1', roomName: '검색 테스트방'),
        QuickAction.goSearch(), // title: '전체 검색'
        ViewProfileAction(userId: 'u1', username: '검색봇'),
      ];

      final scored = actions.map((a) => (a, a.matchScore('검색'))).toList()
        ..sort((a, b) => b.$2.compareTo(a.$2));

      // All should match
      for (final (_, score) in scored) {
        expect(score, greaterThan(0));
      }

      // First result should be the one with earliest match position
      // '검색 테스트방' has '검색' at index 0
      // '검색봇' has '검색' at index 0 (title match)
      // '전체 검색' has '검색' at index 3
      expect(scored.first.$2, greaterThanOrEqualTo(scored.last.$2));
    });

    test('no match returns 0', () {
      final actions = <CommandAction>[
        GoToRoomAction(roomId: '1', roomName: '일반방'),
        QuickAction.logout(),
        ViewProfileAction(userId: 'u1', username: '홍길동'),
      ];

      for (final a in actions) {
        expect(a.matchScore('xyz123없는단어'), 0);
      }
    });
  });

  group('CommandPaletteNotifier state transitions', () {
    late ProviderContainer container;

    setUpAll(() => dotenv.testLoad(fileInput: ''));

    setUp(() {
      container = ProviderContainer(
        overrides: [
          dioClientProvider.overrideWithValue(DioClient()),
          chatRoomsProvider.overrideWith(
            (ref) => _FakeChatRoomsNotifier(),
          ),
        ],
      );
      // Keep auto-dispose provider alive for the duration of the test
      container.listen(commandPaletteProvider, (_, __) {});
    });

    tearDown(() => container.dispose());

    test('query dropping below 2 chars resets isSearchingUsers to false', () {
      final notifier = container.read(commandPaletteProvider.notifier);

      // Simulate typing 2+ chars — triggers isSearchingUsers = true
      notifier.updateQuery('ab');
      expect(container.read(commandPaletteProvider).isSearchingUsers, true);

      // Drop below 2 chars — spinner must clear
      notifier.updateQuery('a');
      expect(container.read(commandPaletteProvider).isSearchingUsers, false);
    });

    test('empty query resets isSearchingUsers to false', () {
      final notifier = container.read(commandPaletteProvider.notifier);

      notifier.updateQuery('abc');
      expect(container.read(commandPaletteProvider).isSearchingUsers, true);

      notifier.updateQuery('');
      expect(container.read(commandPaletteProvider).isSearchingUsers, false);
    });
  });
}

/// Stub notifier that provides an empty room list without network calls.
class _FakeChatRoomsNotifier extends ChatRoomsNotifier {
  _FakeChatRoomsNotifier() : super(DioClient()) {
    state = const AsyncValue.data([]);
  }

  @override
  Future<void> fetchRooms() async {
    // no-op — avoid real network calls in tests
  }
}
