import 'package:gossiplog/features/world/collection_page.dart';
import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'fake_game_api.dart';

const _entries = [
  CollectionEntry(
      topicId: 7,
      dungeonId: 'jvm',
      dungeonName: 'JVM 대장간',
      topicTitle: 'JVM 힙 비율',
      monsterName: 'OOM 슬라임',
      spriteKey: 'monster_jvm',
      caught: true,
      correctCount: 2,
      questionCount: 5,
      dueCount: 1),
  CollectionEntry(
      topicId: 8,
      dungeonId: 'jvm',
      dungeonName: 'JVM 대장간',
      topicTitle: 'GC 로그',
      spriteKey: 'monster_jvm',
      caught: false,
      correctCount: 0,
      questionCount: 3,
      dueCount: 0),
  CollectionEntry(
      topicId: 9,
      dungeonId: 'db',
      dungeonName: 'DB 도서관',
      topicTitle: '인덱스',
      monsterName: '풀스캔 유령',
      spriteKey: 'monster_db',
      caught: true,
      correctCount: 3,
      questionCount: 3,
      dueCount: 0),
];

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
            builder: (_, s) =>
                Scaffold(body: Text('REVIEW ${s.pathParameters['topicId']}')),
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

Future<void> _pump(WidgetTester tester, FakeGameApi api,
    {Size size = const Size(1280, 900)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(_app(api));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  testWidgets('sections per dungeon with caught counts and cards',
      (tester) async {
    await _pump(tester, FakeGameApi()..collectionResult = _entries);
    expect(find.text('JVM 대장간'), findsOneWidget);
    expect(find.text('잡은 몬스터 1/2'), findsOneWidget);
    expect(find.text('DB 도서관'), findsOneWidget);
    expect(find.text('잡은 몬스터 1/1'), findsOneWidget);
    expect(find.text('OOM 슬라임'), findsOneWidget);
    expect(find.text('JVM 힙 비율'), findsOneWidget);
    expect(find.text('정답 2/5'), findsOneWidget);
    expect(find.text('복습 1'), findsOneWidget);
    expect(find.text('???'), findsOneWidget);
    expect(find.text('GC 로그'), findsNothing);
  });

  testWidgets('tap caught → review route', (tester) async {
    await _pump(tester, FakeGameApi()..collectionResult = _entries);
    await tester.tap(find.text('OOM 슬라임'));
    await tester.pumpAndSettle();
    expect(find.text('REVIEW 7'), findsOneWidget);
  });

  testWidgets('tap uncaught → snackbar, stays', (tester) async {
    await _pump(tester, FakeGameApi()..collectionResult = _entries);
    await tester.tap(find.text('???'));
    await tester.pump();
    expect(find.text('아직 잡지 못한 몬스터다. 던전에서 정답을 맞혀 잡아 봐.'), findsOneWidget);
    expect(find.textContaining('REVIEW'), findsNothing);
  });

  testWidgets('empty state', (tester) async {
    await _pump(tester, FakeGameApi());
    expect(find.textContaining('아직 도감이 비어 있다'), findsOneWidget);
  });

  testWidgets('error state retries', (tester) async {
    final api = FakeGameApi()
      ..collectionResult = GameApiException(GameApiException.offline);
    await _pump(tester, api);
    expect(find.text('도감을 불러오지 못했다'), findsOneWidget);
    api.collectionResult = _entries;
    await tester.tap(find.text('다시 시도'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('OOM 슬라임'), findsOneWidget);
    expect(api.collectionCalls, 2);
  });

  testWidgets('no overflow on a narrow phone', (tester) async {
    await _pump(tester, FakeGameApi()..collectionResult = _entries,
        size: const Size(360, 640));
    expect(tester.takeException(), isNull);
  });
}
