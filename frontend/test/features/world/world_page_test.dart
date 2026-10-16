import 'package:chatflow/features/world/admin/admin_game_provider.dart';
import 'package:chatflow/features/world/game/village_game.dart';
import 'package:chatflow/features/world/state/game_api.dart';
import 'package:chatflow/features/world/state/game_models.dart';
import 'package:chatflow/features/world/world_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'fake_game_api.dart';

late VillageGame _village;

Widget _app(FakeGameApi api, {required bool admin}) {
  _village = VillageGame();
  final router = GoRouter(
    initialLocation: '/world',
    routes: [
      GoRoute(
        path: '/world',
        builder: (_, __) => WorldPage(game: _village),
        routes: [
          GoRoute(
            path: 'dungeon/:id',
            builder: (context, s) => Scaffold(
              body: TextButton(
                onPressed: () => context.pop(),
                child: Text('DUNGEON ${s.pathParameters['id']}'),
              ),
            ),
          ),
          GoRoute(
              path: 'admin',
              builder: (_, __) => const Scaffold(body: Text('ADMIN'))),
        ],
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      gameApiProvider.overrideWithValue(api),
      isGameAdminProvider.overrideWith((ref) async => admin),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

FakeGameApi _api() => FakeGameApi()
  ..dungeons = const [
    DungeonInfo(
        id: 'k8s',
        name: '쿠버네티스 성',
        buildingStyle: 'k8s',
        open: true,
        clearedByMe: true,
        clearCount: 4),
  ]
  ..meResult = const GameMe(clearedCount: 8, total: 8, graduated: true)
  ..board = const [
    LeaderboardEntry(username: 'alice', clearedCount: 3, correctCount: 21),
  ];

Future<void> _pump(WidgetTester tester, FakeGameApi api,
    {bool admin = false}) async {
  tester.view.physicalSize = const Size(1280, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_app(api, admin: admin));
  bool ready() => _village.isLoaded && _village.avatar.isLoaded;
  for (var f = 0; f < 60 && !ready(); f++) {
    await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pump(const Duration(milliseconds: 16));
  }
  await tester.pump(const Duration(milliseconds: 16));
  expect(ready(), isTrue);
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    rootBundle.clear();
  });

  testWidgets('admin icon only for game admins', (tester) async {
    await _pump(tester, _api());
    expect(find.byTooltip('문제 검수'), findsNothing);
  });

  testWidgets('admin icon opens /world/admin', (tester) async {
    await _pump(tester, _api(), admin: true);
    await tester.tap(find.byTooltip('문제 검수'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('ADMIN'), findsOneWidget);
  });

  testWidgets('applies world state; door pauses the village until return',
      (tester) async {
    await _pump(tester, _api());
    expect(_village.avatar.graduated, isTrue);

    _village.onDoorReached!('k8s');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('DUNGEON k8s'), findsOneWidget);
    expect(_village.paused, isTrue);

    await tester.tap(find.text('DUNGEON k8s'));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('DUNGEON k8s'), findsNothing);
    expect(_village.paused, isFalse);
  });

  testWidgets('fountain opens the leaderboard sheet', (tester) async {
    await _pump(tester, _api());
    _village.onFountainTapped!();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('광장 랭킹'), findsOneWidget);
    expect(find.text('alice'), findsOneWidget);
    expect(find.text('던전 3곳'), findsOneWidget);
  });
}
