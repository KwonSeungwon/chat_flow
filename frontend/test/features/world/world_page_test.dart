import 'dart:async';

import 'package:flame/game.dart';
import 'package:gossiplog/features/world/admin/admin_game_provider.dart';
import 'package:gossiplog/features/world/game/village_game.dart';
import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:gossiplog/features/world/world_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'fake_game_api.dart';

late VillageGame _village;

/// 페이지가 만든 게임 수(그림을 바꾸면 새로 만든다).
var _created = 0;

Widget _app(FakeGameApi api,
    {required bool admin, String initial = '/world'}) {
  _created = 0;
  final router = GoRouter(
    initialLocation: initial,
    routes: [
      GoRoute(
        path: '/world',
        builder: (_, __) => WorldPage(createGame: (look) {
          _created++;
          return _village = VillageGame(look: look);
        }),
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
  ..meResult = const GameMe(
      clearedCount: 8, total: 8, graduated: true, avatar: StudentLook.boy)
  ..board = const [
    LeaderboardEntry(username: 'alice', clearedCount: 3, correctCount: 21),
  ];

Future<void> _pump(WidgetTester tester, FakeGameApi api,
    {bool admin = false,
    String initial = '/world',
    bool waitForGame = true}) async {
  tester.view.physicalSize = const Size(1280, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_app(api, admin: admin, initial: initial));
  if (!waitForGame) {
    // 엔진이 멈춰 있으면 게임 로드가 진행되지 않는다.
    await tester.pump(const Duration(milliseconds: 400));
    return;
  }
  await _waitForGame(tester);
}

Future<void> _waitForGame(WidgetTester tester) async {
  bool ready() => _created > 0 && _village.isLoaded && _village.avatar.isLoaded;
  for (var f = 0; f < 60 && !ready(); f++) {
    await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pump(const Duration(milliseconds: 16));
  }
  await tester.pump(const Duration(milliseconds: 16));
  expect(ready(), isTrue);
}

const _pickTitle = '어떤 학생으로 입학할래?';

FakeGameApi _newcomer() => _api()
  ..meResult = const GameMe(clearedCount: 0, total: 8, graduated: false);

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

  testWidgets('deep-linked dungeon keeps the village paused until popped',
      (tester) async {
    await _pump(tester, _api(),
        initial: '/world/dungeon/k8s', waitForGame: false);
    expect(find.text('DUNGEON k8s'), findsOneWidget);
    expect(_village.paused, isTrue);

    await tester.tap(find.text('DUNGEON k8s'));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('DUNGEON k8s'), findsNothing);
    expect(_village.paused, isFalse);
  });

  testWidgets('picker hidden when the server already has an avatar',
      (tester) async {
    await _pump(tester, _api());
    expect(find.text(_pickTitle), findsNothing);
    expect(_village.look, StudentLook.boy);
  });

  testWidgets(
      'no avatar: picker first, no village until chosen; choosing saves',
      (tester) async {
    final api = _newcomer();
    await _pump(tester, api, waitForGame: false);
    expect(find.text(_pickTitle), findsOneWidget);
    expect(find.text('나중에 마을 메뉴에서 바꿀 수 있어요.'), findsOneWidget);
    expect(_created, 0);
    expect(find.byType(GameWidget<VillageGame>), findsNothing);

    await tester.tap(find.text('여학생'));
    await tester.pump();
    await tester.tap(find.text('이 학생으로 입학!'));
    await tester.pump();

    expect(api.avatarCalls, [StudentLook.girl]);
    expect(find.text(_pickTitle), findsNothing);
    await _waitForGame(tester);
    expect(_village.look, StudentLook.girl);
    expect(_village.avatar.look, StudentLook.girl);
  });

  testWidgets('double tap on enroll while saving sends one setAvatar',
      (tester) async {
    final gate = Completer<void>();
    final api = _newcomer()..avatarGate = gate.future;
    await _pump(tester, api, waitForGame: false);

    await tester.tap(find.text('이 학생으로 입학!'));
    await tester.tap(find.text('이 학생으로 입학!'), warnIfMissed: false);
    await tester.pump();
    expect(api.avatarCalls, [StudentLook.boy]);

    gate.complete();
    await tester.pump();
    expect(api.avatarCalls, [StudentLook.boy]);
  });

  testWidgets('save failure: snackbar, choice still used this session',
      (tester) async {
    final api = _newcomer()
      ..avatarError = GameApiException(GameApiException.offline);
    await _pump(tester, api, waitForGame: false);
    await tester.tap(find.text('이 학생으로 입학!'));
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('저장하지 못했다'), findsOneWidget);
    expect(find.text(_pickTitle), findsNothing);
    await _waitForGame(tester);
    expect(_village.look, StudentLook.boy);
  });

  testWidgets('menu reopens the picker; choosing switches the village look',
      (tester) async {
    final api = _api();
    await _pump(tester, api);
    final first = _village;

    await tester.tap(find.byTooltip('마을 메뉴'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.text('캐릭터 바꾸기'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text(_pickTitle), findsOneWidget);
    expect(first.paused, isTrue);

    await tester.tap(find.text('여학생'));
    await tester.pump();
    await tester.tap(find.text('이 학생으로 입학!'));
    await tester.pump();
    expect(api.avatarCalls, [StudentLook.girl]);
    expect(find.text(_pickTitle), findsNothing);
    await _waitForGame(tester);
    expect(identical(_village, first), isFalse);
    expect(_village.look, StudentLook.girl);
    expect(_village.paused, isFalse);
  });

  testWidgets('reopened picker can be closed without changing anything',
      (tester) async {
    final api = _api();
    await _pump(tester, api);
    final first = _village;
    await tester.tap(find.byTooltip('마을 메뉴'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.text('캐릭터 바꾸기'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.byTooltip('닫기'));
    await tester.pump();
    expect(find.text(_pickTitle), findsNothing);
    expect(api.avatarCalls, isEmpty);
    expect(identical(_village, first), isTrue);
    expect(first.paused, isFalse);
  });

  testWidgets('/me failure: boy village, no picker; retry with avatar swaps',
      (tester) async {
    final api = _api()..meError = GameApiException(GameApiException.offline);
    await _pump(tester, api);
    expect(find.text(_pickTitle), findsNothing);
    expect(find.text('던전 정보를 불러오지 못했다'), findsOneWidget);
    expect(_village.look, StudentLook.boy);
    expect(_village.paused, isFalse);

    api
      ..meError = null
      ..meResult = const GameMe(
          clearedCount: 0, total: 8, graduated: false, avatar: StudentLook.girl);
    await tester.tap(find.text('다시 시도'));
    await tester.pump();
    await _waitForGame(tester);
    expect(_created, 2);
    expect(_village.look, StudentLook.girl);
    expect(find.text(_pickTitle), findsNothing);
  });

  testWidgets('/me failure then retry without avatar shows the picker',
      (tester) async {
    final api = _newcomer()..meError = GameApiException(GameApiException.offline);
    await _pump(tester, api);
    final fallback = _village;
    expect(find.text(_pickTitle), findsNothing);

    api.meError = null;
    await tester.tap(find.text('다시 시도'));
    await tester.pump();
    await tester.pump();
    expect(find.text(_pickTitle), findsOneWidget);
    expect(fallback.paused, isTrue);

    await tester.tap(find.text('이 학생으로 입학!'));
    await tester.pump();
    expect(api.avatarCalls, [StudentLook.boy]);
    expect(find.text(_pickTitle), findsNothing);
    expect(identical(_village, fallback), isTrue);
    expect(fallback.paused, isFalse);
  });
}
