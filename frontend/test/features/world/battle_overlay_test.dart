import 'package:gossiplog/features/world/battle/battle_overlay.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';

const _enc = Encounter(
  seq: 1,
  monster:
      MonsterInfo(name: 'OOM 슬라임', spriteKey: 'monster_jvm', isBoss: false),
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
  StudentLook? look,
  Encounter encounter = _enc,
}) {
  return MediaQuery(
    data: MediaQueryData(size: size),
    child: MaterialApp(
      home: look == null
          ? BattleOverlay(
              dungeonId: 'jvm',
              dungeonName: 'JVM 대장간',
              encounter: encounter,
              hearts: result?.hearts ?? 3,
              kills: 3,
              killsToBoss: 5,
              selectedIndex: selected,
              result: result,
              onChoice: (i) => taps?.add(i),
              onContinue: onContinue ?? () {},
            )
          : BattleOverlay(
              dungeonId: 'jvm',
              dungeonName: 'JVM 대장간',
              encounter: encounter,
              look: look,
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

Future<void> _setSize(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

String _studentAsset(WidgetTester tester) {
  final svg =
      tester.widget<SvgPicture>(find.byKey(const ValueKey('student_back')));
  return (svg.bytesLoader as SvgAssetLoader).assetName;
}

const _boss = Encounter(
  seq: 6,
  monster: MonsterInfo(name: '힙 드래곤', spriteKey: 'boss_jvm', isBoss: true),
  question: BattleQuestion(
    id: 2,
    stem:
        '아주 긴 문제 지문이다. 컨테이너 메모리 한도 안에서 JVM 힙과 메타스페이스, 스레드 스택, 다이렉트 버퍼가 어떻게 나뉘는지 생각해 보고 가장 먼저 조정할 옵션을 고른다면 무엇인가?',
    choices: [
      '-Xss를 늘려서 스레드 스택을 넉넉하게 잡고 GC 로그를 끈다',
      'MaxRAMPercentage를 낮춰 비힙 영역에 여유를 준다',
      'liveness probe 주기를 늘려서 재시작을 늦춘다',
      '로그 레벨을 DEBUG로 올려 원인을 더 자세히 본다',
    ],
    topicTitle: 'JVM 힙 비율',
  ),
);

OutlinedButton _choice(WidgetTester t, int i) =>
    t.widget<OutlinedButton>(find.descendant(
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
    await tester.ensureVisible(find.byKey(const ValueKey('choice_2')));
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
    await tester.ensureVisible(find.byKey(const ValueKey('battle_continue')));
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

  testWidgets('wide screens also stack, centered, at most 560 wide',
      (tester) async {
    await _setSize(tester, const Size(1280, 800));
    await tester.pumpWidget(_host(size: const Size(1280, 800)));
    final scene = tester.getTopLeft(find.byKey(const ValueKey('monster')));
    final choice = tester.getTopLeft(find.byKey(const ValueKey('choice_0')));
    expect(choice.dy, greaterThan(scene.dy));
    final card = tester.getRect(find.byKey(const ValueKey('choice_0')));
    final other = tester.getRect(find.byKey(const ValueKey('choice_1')));
    // 2×2: 같은 줄, 둘을 합친 폭은 560 안, 가운데 정렬
    expect(other.top, card.top);
    expect(other.right - card.left, lessThanOrEqualTo(560));
    expect((card.left + other.right) / 2, closeTo(640, 20));
  });

  testWidgets('shows the encounter line in the message box', (tester) async {
    await tester.pumpWidget(_host());
    expect(find.textContaining('야생의 OOM 슬라임이 나타났다!', findRichText: true),
        findsOneWidget);
  });

  Encounter named(String name) => Encounter(
        seq: 1,
        monster:
            MonsterInfo(name: name, spriteKey: 'monster_jvm', isBoss: false),
        question: _enc.question,
      );
  Finder rich(String text) => find.textContaining(text, findRichText: true);
  AnswerResult answered(
          {required bool correct, bool cleared = false, bool failed = false}) =>
      AnswerResult(
        correct: correct,
        correctIndex: 1,
        explanation: 'x',
        topicTitle: 'JVM 힙 비율',
        hearts: 2,
        kills: 3,
        bossUnlocked: false,
        cleared: cleared,
        failed: failed,
      );

  testWidgets('message picks 가/를 for names without a final consonant',
      (tester) async {
    await tester.pumpWidget(_host(encounter: named('해파리')));
    expect(rich('야생의 해파리가 나타났다!'), findsOneWidget);
    await tester.pumpWidget(_host(
        encounter: named('해파리'), selected: 1, result: answered(correct: true)));
    expect(rich('해파리를 물리쳤다!'), findsOneWidget);
    expect(rich('정답이다!'), findsOneWidget);
  });

  testWidgets('message uses 이(가)/을(를) for non-Hangul names', (tester) async {
    await tester.pumpWidget(_host(encounter: named('OOM')));
    expect(rich('야생의 OOM이(가) 나타났다!'), findsOneWidget);
    await tester.pumpWidget(_host(
        encounter: named('OOM'),
        selected: 1,
        result: answered(correct: true, cleared: true)));
    expect(rich('OOM을(를) 물리쳤다!'), findsOneWidget);
    expect(rich('던전을 정복했다!'), findsOneWidget);
  });

  testWidgets('wrong answer shows the counterattack line', (tester) async {
    await tester.pumpWidget(_host(selected: 0, result: _wrong));
    expect(rich('OOM 슬라임의 반격! ♥ -1'), findsOneWidget);
    expect(rich('아래 해설을 읽어 보자.'), findsOneWidget);
    await tester.pumpWidget(
        _host(selected: 0, result: answered(correct: false, failed: true)));
    expect(rich('하트를 모두 잃었다…'), findsOneWidget);
  });

  testWidgets('boss gets its own message and badge', (tester) async {
    await tester.pumpWidget(_host(encounter: _boss));
    expect(find.textContaining('보스 힙 드래곤이 앞을 막아섰다!', findRichText: true),
        findsOneWidget);
    expect(find.text('보스전'), findsOneWidget);
    expect(find.text('보스전 · 3연속 정답'), findsOneWidget);
  });

  testWidgets('student back view defaults to boy', (tester) async {
    await tester.pumpWidget(_host());
    expect(_studentAsset(tester), 'assets/world/student_boy_back.svg');
  });

  testWidgets('student back view follows the chosen look', (tester) async {
    await tester.pumpWidget(_host(look: StudentLook.girl));
    expect(_studentAsset(tester), 'assets/world/student_girl_back.svg');
    await tester.pumpWidget(_host(look: StudentLook.boy));
    expect(_studentAsset(tester), 'assets/world/student_boy_back.svg');
  });

  for (final size in const [
    Size(390, 844),
    Size(360, 640),
    Size(844, 390),
    Size(1280, 800),
  ]) {
    testWidgets('no overflow with long text at $size', (tester) async {
      await _setSize(tester, size);
      await tester.pumpWidget(_host(size: size, encounter: _boss));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(
          _host(size: size, encounter: _boss, selected: 0, result: _wrong));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.textContaining('MaxRAMPercentage를 낮춰 비힙 영역에 여유를 준다 ✓'),
          findsOneWidget);
    });
  }
}
