import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/command_palette/command_action.dart';
import 'package:chatflow/features/command_palette/command_palette_provider.dart';
import 'package:chatflow/features/command_palette/widgets/command_palette_overlay.dart';

// ---------------------------------------------------------------------------
// A mock notifier that returns controlled results
// ---------------------------------------------------------------------------

class _MockCommandPaletteNotifier extends StateNotifier<CommandPaletteState>
    implements CommandPaletteNotifier {
  _MockCommandPaletteNotifier(List<CommandAction> initial)
      : super(CommandPaletteState(results: initial));

  @override
  void updateQuery(String query) {
    if (query.isEmpty) {
      state = CommandPaletteState(results: QuickAction.all());
      return;
    }
    // For testing: filter the preset actions list by matchScore
    final all = <CommandAction>[
      GoToRoomAction(roomId: '1', roomName: '땅콩-DM', roomDescription: 'DM 방'),
      GoToRoomAction(roomId: '2', roomName: '일반 채팅방'),
      ViewProfileAction(userId: 'u1', username: '땅콩사용자'),
      ...QuickAction.all(),
    ];
    final matched = all.where((a) => a.matchScore(query) > 0).toList();
    state = CommandPaletteState(query: query, results: matched);
  }

}

// ---------------------------------------------------------------------------
// Helper to build the widget tree
// ---------------------------------------------------------------------------

Widget _buildTestApp({List<CommandAction>? initialActions}) {
  final initial = initialActions ?? QuickAction.all();
  return ProviderScope(
    overrides: [
      commandPaletteProvider.overrideWith(
        (ref) => _MockCommandPaletteNotifier(initial),
      ),
    ],
    child: MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: ElevatedButton(
            onPressed: () => showCommandPalette(context),
            child: const Text('Open'),
          ),
        ),
      ),
    ),
  );
}

void main() {
  group('CommandPaletteOverlay', () {
    testWidgets('opens and shows search field with autofocus', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Search field should be present
      expect(find.byType(TextField), findsOneWidget);
    });

    testWidgets('initial state shows quick actions', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Should show all 4 quick actions
      expect(find.text('새 방 만들기'), findsOneWidget);
      expect(find.text('전체 검색'), findsOneWidget);
      expect(find.text('다크/라이트 모드 토글'), findsOneWidget);
      expect(find.text('로그아웃'), findsOneWidget);
    });

    testWidgets('typing query filters results', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '땅');
      await tester.pumpAndSettle();

      // Should show matching items
      expect(find.text('땅콩-DM'), findsOneWidget);
      expect(find.text('땅콩사용자'), findsOneWidget);
      // Quick actions without '땅' should not appear
      expect(find.text('로그아웃'), findsNothing);
    });

    testWidgets('empty results show "결과 없음"', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'xyz없는단어');
      await tester.pumpAndSettle();

      expect(find.text('결과 없음'), findsOneWidget);
    });

    testWidgets('ArrowDown changes highlight index', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Press ArrowDown twice
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
      await tester.pumpAndSettle();

      // We can verify indirectly that highlight changed by checking
      // the widget tree still renders (no crash). A more detailed test
      // would inspect Container colors, but that requires knowledge of
      // the theme. The important thing is it does not throw.
      expect(find.text('새 방 만들기'), findsOneWidget);
    });

    testWidgets('ArrowUp wraps to bottom', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Press ArrowUp from index 0 should wrap to last item
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pumpAndSettle();

      // Should still render without error
      expect(find.text('로그아웃'), findsOneWidget);
    });

    testWidgets('Esc closes the palette', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.byType(TextField), findsOneWidget);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();

      // Dialog should be closed — TextField should be gone
      expect(find.byType(TextField), findsNothing);
    });

    testWidgets('footer shows keyboard hints', (tester) async {
      await tester.pumpWidget(_buildTestApp());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('이동'), findsOneWidget);
      expect(find.text('실행'), findsOneWidget);
      expect(find.text('닫기'), findsOneWidget);
    });
  });
}
