import 'package:chatflow/features/world/battle/battle_overlay.dart';
import 'package:chatflow/features/world/state/game_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const _enc = Encounter(
  seq: 1,
  monster: MonsterInfo(name: 'OOM 슬라임', spriteKey: 'monster_jvm', isBoss: false),
  question: BattleQuestion(
    id: 1,
    stem: '컨테이너가 OOMKilled로 재시작된다. 먼저 의심할 것은?',
    choices: ['GC 알고리즘', 'MaxRAMPercentage', 'probe 주기', '로그 레벨'],
    topicTitle: 'JVM 힙 비율',
  ),
);

const _wrong = AnswerResult(
  correct: false,
  correctIndex: 1,
  explanation: '비힙 공간이 밀린다.',
  topicTitle: 'JVM 힙 비율',
  sourceRef: 'wiki/jvm.md',
  hearts: 2,
  kills: 3,
  bossUnlocked: false,
  cleared: false,
  failed: false,
);

Widget _host({
  int? selected,
  AnswerResult? result,
  List<int>? taps,
  VoidCallback? onContinue,
  Size size = const Size(1280, 900),
}) {
  return MediaQuery(
    data: MediaQueryData(size: size),
    child: MaterialApp(
      home: BattleOverlay(
        dungeonId: 'jvm',
        dungeonName: 'JVM 대장간',
        encounter: _enc,
        hearts: result?.hearts ?? 3,
        kills: 3,
        killsToBoss: 5,
        selectedIndex: selected,
        result: result,
        onChoice: (i) => taps?.add(i),
        onContinue: onContinue ?? () {},
      ),
    ),
  );
}

OutlinedButton _choice(WidgetTester t, int i) => t.widget<OutlinedButton>(
    find.descendant(
        of: find.byKey(ValueKey('choice_$i')),
        matching: find.byType(OutlinedButton)));

void main() {
  testWidgets('shows four choices and reports the tap', (tester) async {
    final taps = <int>[];
    await tester.pumpWidget(_host(taps: taps));
    for (var i = 0; i < 4; i++) {
      expect(find.byKey(ValueKey('choice_$i')), findsOneWidget);
    }
    expect(find.text('보스까지 2마리'), findsOneWidget);
    expect(find.text('처치 3/5'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('choice_2')));
    expect(taps, [2]);
    expect(find.byKey(const ValueKey('battle_feedback')), findsNothing);
  });

  testWidgets('after selecting, choices are disabled', (tester) async {
    final taps = <int>[];
    await tester.pumpWidget(_host(selected: 0, taps: taps));
    for (var i = 0; i < 4; i++) {
      expect(_choice(tester, i).onPressed, isNull);
    }
    await tester.tap(find.byKey(const ValueKey('choice_3')));
    expect(taps, isEmpty);
  });

  testWidgets('after answering shows explanation, source and correct mark',
      (tester) async {
    var continued = 0;
    await tester.pumpWidget(
        _host(selected: 0, result: _wrong, onContinue: () => continued++));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('battle_feedback')), findsOneWidget);
    expect(find.textContaining('비힙 공간이 밀린다.'), findsOneWidget);
    expect(find.textContaining('원문 주제 · JVM 힙 비율'), findsOneWidget);
    expect(find.textContaining('wiki/jvm.md'), findsOneWidget);
    expect(find.textContaining('MaxRAMPercentage ✓'), findsOneWidget);
    expect(find.textContaining('GC 알고리즘 ✗'), findsOneWidget);
    expect(find.text('♥ ♥ ♡'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('battle_continue')));
    expect(continued, 1);
  });

  testWidgets('narrow screens stack the panels vertically', (tester) async {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(_host(size: const Size(400, 900)));
    final scene = tester.getTopLeft(find.byKey(const ValueKey('monster')));
    final choice = tester.getTopLeft(find.byKey(const ValueKey('choice_0')));
    expect(choice.dy, greaterThan(scene.dy));
  });

  test('monsterAsset falls back for unknown sprite keys', () {
    expect(monsterAsset(_enc.monster, 'jvm'), 'assets/world/monster_jvm.svg');
    expect(
        monsterAsset(
            const MonsterInfo(name: 'x', spriteKey: '../evil', isBoss: true),
            'db'),
        'assets/world/boss_db.svg');
  });
}
