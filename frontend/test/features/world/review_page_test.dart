import 'package:gossiplog/features/world/battle/battle_overlay.dart';
import 'package:gossiplog/features/world/collection_page.dart';
import 'package:gossiplog/features/world/review_page.dart';
import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'fake_game_api.dart';

const _entry = CollectionEntry(
    topicId: 7,
    dungeonId: 'jvm',
    dungeonName: 'JVM 대장간',
    topicTitle: 'JVM 힙 비율',
    monsterName: 'OOM 슬라임',
    spriteKey: 'monster_jvm',
    caught: true,
    correctCount: 2,
    questionCount: 5,
    dueCount: 1);

Widget _app(FakeGameApi api) {
  final router = GoRouter(
    initialLocation: '/world/collection',
    routes: [
      GoRoute(
        path: '/world',
        builder: (_, __) => const Scaffold(body: Text('VILLAGE')),
        routes: [
          GoRoute(
              path: 'collection', builder: (_, __) => const CollectionPage()),
          GoRoute(
            path: 'review/:topicId',
            builder: (_, s) => ReviewPage(
                topicId: int.tryParse(s.pathParameters['topicId'] ?? '') ?? -1),
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

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 8; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _open(WidgetTester tester, FakeGameApi api) async {
  tester.view.physicalSize = const Size(1280, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_app(api));
  await _settle(tester);
  await tester.tap(find.text('OOM 슬라임'));
  await _settle(tester);
}

Future<void> _answer(WidgetTester tester, int choice) async {
  await tester.ensureVisible(find.byKey(ValueKey('choice_$choice')));
  await tester.tap(find.byKey(ValueKey('choice_$choice')));
  await _settle(tester);
}

Future<void> _continue(WidgetTester tester) async {
  await tester.ensureVisible(find.byKey(const ValueKey('battle_continue')));
  await tester.tap(find.byKey(const ValueKey('battle_continue')));
  await _settle(tester);
}

FakeGameApi _api() => FakeGameApi()..collectionResult = const [_entry];

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  testWidgets('starts review, chains questions, shows the score dialog',
      (tester) async {
    final api = _api()
      ..nexts.addAll([fakeEncounter(1), fakeEncounter(2, hp: 15)])
      ..answers.addAll([
        fakeResult(
            review: true,
            reviewCorrect: 1,
            reviewTotal: 2,
            damage: 15,
            monsterHp: 15,
            monsterDefeated: false),
        fakeResult(
            review: true,
            reviewCorrect: 2,
            reviewTotal: 2,
            damage: 15,
            monsterHp: 0,
            monsterDefeated: true,
            cleared: true),
      ]);
    await _open(tester, api);
    expect(api.reviewCalls, [7]);
    expect(find.byType(BattleOverlay), findsOneWidget);
    expect(find.text('복습 1/2'), findsOneWidget);

    await _answer(tester, 0);
    expect(find.text('15 데미지!'), findsOneWidget);
    expect(find.text('복습 1/2'), findsOneWidget);
    await _continue(tester);
    expect(find.text('Q2'), findsOneWidget);
    expect(find.text('복습 2/2'), findsOneWidget);

    await _answer(tester, 0);
    await _continue(tester);
    expect(find.text('복습 완료!'), findsOneWidget);
    expect(find.textContaining('2/2 정답'), findsOneWidget);
    expect(find.textContaining('몬스터를 다시 쓰러뜨렸다!'), findsOneWidget);

    final before = api.collectionCalls;
    await tester.tap(find.text('도감으로'));
    await _settle(tester);
    expect(find.byType(ReviewPage), findsNothing);
    expect(find.byType(CollectionPage), findsOneWidget);
    expect(api.collectionCalls, greaterThan(before));
  });

  testWidgets('not all correct: no defeat line', (tester) async {
    final api = _api()
      ..reviewStartResult = const RunStart(
          runId: 'rv',
          hearts: 3,
          kills: 0,
          killsToBoss: 3,
          review: true,
          reviewTotal: 1)
      ..nexts.add(fakeEncounter(1))
      ..answers.add(fakeResult(
          correct: false,
          review: true,
          reviewCorrect: 0,
          reviewTotal: 1,
          monsterHp: 15,
          monsterDefeated: false,
          cleared: true));
    await _open(tester, api);
    await _answer(tester, 1);
    await _continue(tester);
    expect(find.textContaining('0/1 정답'), findsOneWidget);
    expect(find.textContaining('몬스터를 다시 쓰러뜨렸다!'), findsNothing);
  });

  testWidgets('NOT_CAUGHT → message and back button', (tester) async {
    final api = _api()
      ..reviewStartResult =
          GameApiException(GameApiException.notCaught, statusCode: 409);
    await _open(tester, api);
    expect(find.textContaining('아직 잡지 못한 몬스터다'), findsOneWidget);
    await tester.tap(find.text('도감으로'));
    await _settle(tester);
    expect(find.byType(CollectionPage), findsOneWidget);
  });

  testWidgets('expired run → message', (tester) async {
    final api = _api()
      ..nexts.add(GameApiException(GameApiException.runExpired));
    await _open(tester, api);
    expect(find.textContaining('복습 판이 사라졌다'), findsOneWidget);
    expect(find.text('도감으로'), findsOneWidget);
  });

  testWidgets('offline first question → retry loads it', (tester) async {
    final api = _api()
      ..nexts.addAll(
          [GameApiException(GameApiException.offline), fakeEncounter(1)]);
    await _open(tester, api);
    expect(find.byType(BattleOverlay), findsNothing);
    await tester.tap(find.text('다시 시도'));
    await _settle(tester);
    expect(find.byType(BattleOverlay), findsOneWidget);
  });
}
