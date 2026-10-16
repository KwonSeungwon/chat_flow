import 'package:chatflow/features/world/dungeon_page.dart';
import 'package:chatflow/features/world/game/dungeon_game.dart';
import 'package:chatflow/features/world/state/game_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'fake_game_api.dart';

late DungeonGame _game;

Widget _app(FakeGameApi api) {
  _game = DungeonGame('k8s');
  final router = GoRouter(
    initialLocation: '/world',
    routes: [
      GoRoute(
        path: '/world',
        builder: (context, _) => Scaffold(
          body: TextButton(
            onPressed: () => context.push('/world/dungeon/k8s'),
            child: const Text('VILLAGE'),
          ),
        ),
        routes: [
          GoRoute(
            path: 'dungeon/:id',
            builder: (_, s) =>
                DungeonPage(dungeonId: s.pathParameters['id']!, game: _game),
          ),
        ],
      ),
    ],
  );
  return ProviderScope(
    overrides: [gameApiProvider.overrideWithValue(api)],
    child: MaterialApp.router(routerConfig: router),
  );
}

Future<void> _enter(WidgetTester tester, FakeGameApi api,
    {bool waitForGame = true}) async {
  tester.view.physicalSize = const Size(1280, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_app(api));
  await tester.pump();
  await tester.tap(find.text('VILLAGE'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
  if (!waitForGame) return;
  bool ready() => _game.isLoaded && _game.avatar.isLoaded;
  for (var f = 0; f < 40 && !ready(); f++) {
    await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pump(const Duration(milliseconds: 16));
  }
  await tester.pump(const Duration(milliseconds: 16));
  expect(ready(), isTrue);
}

/// 게임이 인카운터를 낸 것처럼: 스스로 멈추고 onEncounter를 부른다.
Future<void> _encounter(WidgetTester tester) async {
  _game.pause();
  _game.onEncounter!();
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 16));
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    // 앞 테스트의 fake-async 존에서 만든 에셋 Future가 캐시에 남으면 다음 테스트의
    // SVG 읽기가 끝나지 않는다.
    rootBundle.clear();
  });

  testWidgets('encounter → battle overlay → answer → continue resumes',
      (tester) async {
    final api = FakeGameApi()
      ..nexts.add(fakeEncounter(1))
      ..answers.add(fakeResult(kills: 1));
    await _enter(tester, api);
    expect(_game.isPaused, isFalse, reason: 'run started → walking');

    await _encounter(tester);
    expect(_game.overlays.isActive(battleOverlayKey), isTrue);
    expect(_game.isPaused, isTrue);
    expect(find.byKey(const ValueKey('choice_0')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('choice_0')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 700));
    expect(find.byKey(const ValueKey('battle_feedback')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('battle_continue')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    expect(_game.overlays.isActive(battleOverlayKey), isFalse);
    expect(_game.isPaused, isFalse);
  });

  testWidgets('offline encounter shows a snackbar and keeps walking',
      (tester) async {
    final api = FakeGameApi()
      ..nexts.add(GameApiException(GameApiException.offline));
    await _enter(tester, api);
    await _encounter(tester);
    expect(_game.overlays.isActive(battleOverlayKey), isFalse);
    expect(_game.isPaused, isFalse);
    expect(find.byType(SnackBar), findsWidgets);
  });

  testWidgets('RUN_EXPIRED → "던전이 무너졌다" then back to the village',
      (tester) async {
    final api = FakeGameApi()
      ..nexts.add(GameApiException(GameApiException.runExpired));
    await _enter(tester, api);
    await _encounter(tester);
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('던전이 무너졌다'), findsOneWidget);
    await tester.tap(find.text('확인'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.text('VILLAGE'), findsOneWidget);
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('closed dungeon → snackbar and back', (tester) async {
    final api = FakeGameApi()
      ..startResult = GameApiException(GameApiException.dungeonClosed);
    await _enter(tester, api, waitForGame: false);
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.byType(DungeonPage), findsNothing);
    expect(find.byType(SnackBar), findsWidgets);
  });
}
