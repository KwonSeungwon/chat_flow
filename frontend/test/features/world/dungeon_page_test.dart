import 'package:gossiplog/features/world/battle/battle_overlay.dart';
import 'package:gossiplog/features/world/dungeon_page.dart';
import 'package:gossiplog/features/world/game/dungeon_game.dart';
import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'fake_game_api.dart';

/// 지금 화면에 붙은 던전 게임(페이지가 직접 만든 인스턴스).
DungeonGame get _game => _tester
    .widget<GameWidget<DungeonGame>>(find.byType(GameWidget<DungeonGame>))
    .game!;
late WidgetTester _tester;

Widget _app(FakeGameApi api) {
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
                DungeonPage(dungeonId: s.pathParameters['id']!),
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
  _tester = tester;
  tester.view.physicalSize = const Size(1280, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_app(api));
  await tester.pump();
  await _push(tester, waitForGame: waitForGame);
}

Future<void> _push(WidgetTester tester, {bool waitForGame = true}) async {
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

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 8; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// 인카운터 → 첫 선택지 → 계속.
Future<void> _answerAndContinue(WidgetTester tester) async {
  await _encounter(tester);
  await tester.tap(find.byKey(const ValueKey('choice_0')));
  await _settle(tester);
  await tester.tap(find.byKey(const ValueKey('battle_continue')));
  await _settle(tester);
}

/// 다이얼로그 [title]이 떠 있으면 확인을 누른다.
Future<void> _confirm(WidgetTester tester, String title) async {
  expect(find.text(title), findsOneWidget);
  await tester.tap(find.text('확인'));
  await _settle(tester);
}

const _k8sCleared = DungeonInfo(
    id: 'k8s',
    name: '쿠버네티스 성',
    buildingStyle: 'k8s',
    open: true,
    clearedByMe: true,
    clearCount: 1);

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

  testWidgets('cleared → 클리어 dialog → village', (tester) async {
    final api = FakeGameApi()
      ..nexts.add(fakeEncounter(8, boss: true))
      ..answers.add(fakeResult(kills: 5, boss: true, cleared: true));
    await _enter(tester, api);
    await _answerAndContinue(tester);
    await _confirm(tester, '클리어!');
    expect(find.text('졸업!'), findsNothing);
    expect(find.text('VILLAGE'), findsOneWidget);
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('first 8/8 clear → 졸업 dialog after 클리어', (tester) async {
    final api = FakeGameApi()
      ..nexts.add(fakeEncounter(8, boss: true))
      ..answers.add(fakeResult(kills: 5, boss: true, cleared: true));
    await _enter(tester, api); // 마을 데이터: 아직 졸업 전
    api.meResult = const GameMe(clearedCount: 8, total: 8, graduated: true);
    await _answerAndContinue(tester);
    await _confirm(tester, '클리어!');
    await _confirm(tester, '졸업!');
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('already graduated → no 졸업 dialog', (tester) async {
    final api = FakeGameApi()
      ..meResult = const GameMe(clearedCount: 8, total: 8, graduated: true)
      ..nexts.add(fakeEncounter(8, boss: true))
      ..answers.add(fakeResult(kills: 5, boss: true, cleared: true));
    await _enter(tester, api);
    await _answerAndContinue(tester);
    await _confirm(tester, '클리어!');
    expect(find.text('졸업!'), findsNothing);
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('failed → 기절 dialog → village', (tester) async {
    final api = FakeGameApi()
      ..nexts.add(fakeEncounter(1))
      ..answers
          .add(fakeResult(correct: false, hearts: 0, failed: true));
    await _enter(tester, api);
    await _answerAndContinue(tester);
    await _confirm(tester, '기절했다…');
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('RUN_EXPIRED after answering and now cleared → 클리어 flow',
      (tester) async {
    final api = FakeGameApi()
      ..nexts.add(fakeEncounter(8, boss: true))
      ..answers.add(GameApiException(GameApiException.runExpired));
    await _enter(tester, api);
    api.dungeons = const [_k8sCleared];
    await _encounter(tester);
    await tester.tap(find.byKey(const ValueKey('choice_0')));
    await _settle(tester);
    expect(find.text('던전이 무너졌다'), findsNothing);
    await _confirm(tester, '클리어!');
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('RUN_EXPIRED after answering, not cleared → 판이 끝났다',
      (tester) async {
    final api = FakeGameApi()
      ..nexts.add(fakeEncounter(1))
      ..answers.add(GameApiException(GameApiException.runExpired));
    await _enter(tester, api);
    await _encounter(tester);
    await tester.tap(find.byKey(const ValueKey('choice_0')));
    await _settle(tester);
    expect(find.text('던전이 무너졌다'), findsNothing);
    await _confirm(tester, '판이 끝났다');
    expect(find.byType(DungeonPage), findsNothing);
  });

  testWidgets('push and pop the dungeon 3 times', (tester) async {
    final api = FakeGameApi();
    await _enter(tester, api);
    final seen = <DungeonGame>{};
    for (var i = 0; i < 3; i++) {
      if (i > 0) await _push(tester);
      seen.add(_game);
      expect(_game.isPaused, isFalse);
      await tester.tap(find.byTooltip('마을로'));
      await _settle(tester);
      expect(find.byType(DungeonPage), findsNothing);
      expect(find.text('VILLAGE'), findsOneWidget);
    }
    expect(seen, hasLength(3), reason: 'a new game per page');
    expect(tester.takeException(), isNull);
  });

  testWidgets('deep link waits for the world data and uses the server look',
      (tester) async {
    final api = FakeGameApi()
      ..meResult = const GameMe(
          clearedCount: 0, total: 8, graduated: false, avatar: StudentLook.girl)
      ..nexts.add(fakeEncounter(1));
    await _enter(tester, api);
    expect(_game.look, StudentLook.girl);
    await _encounter(tester);
    expect(
        tester.widget<BattleOverlay>(find.byType(BattleOverlay)).look,
        StudentLook.girl);
  });

  testWidgets('world data failure → dungeon starts as the boy', (tester) async {
    final api = FakeGameApi()
      ..meError = GameApiException(GameApiException.offline);
    await _enter(tester, api);
    expect(_game.look, StudentLook.boy);
  });
}
