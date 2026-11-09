import 'dart:async';

import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:gossiplog/features/world/state/run_notifier.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fake_game_api.dart';

void main() {
  late FakeGameApi api;
  late RunNotifier n;

  setUp(() async {
    api = FakeGameApi();
    n = RunNotifier(api, 'k8s', retryDelay: Duration.zero);
    await n.start();
  });

  test('start → exploring with run values', () {
    expect(n.state.phase, RunPhase.exploring);
    expect(n.state.runId, 'r');
    expect(n.state.hearts, 3);
  });

  test('correct answer counts a kill and returns to exploring', () async {
    api.nexts.add(fakeEncounter(1));
    api.answers.add(fakeResult(kills: 1));
    expect(await n.encounter(), isTrue);
    expect(n.state.phase, RunPhase.battle);
    await n.answer(0);
    expect(n.state.result!.correct, isTrue);
    expect(n.state.kills, 1);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.exploring);
    expect(n.state.encounter, isNull);
  });

  test('fifth kill unlocks boss; boss battles chain; clear', () async {
    api.nexts.add(fakeEncounter(5));
    api.answers.add(fakeResult(kills: 5, boss: true));
    await n.encounter();
    await n.answer(0);
    expect(n.state.bossUnlocked, isTrue);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.exploring);

    api.nexts.addAll([fakeEncounter(6, boss: true), fakeEncounter(7, boss: true)]);
    api.answers.addAll([
      fakeResult(kills: 5, boss: true),
      fakeResult(kills: 5, boss: true, cleared: true),
    ]);
    await n.encounter();
    expect(n.state.encounter!.monster.isBoss, isTrue);
    await n.answer(0);
    await n.finishBattle(); // 보스 단계면 바로 다음 문제
    expect(n.state.phase, RunPhase.battle);
    expect(n.state.encounter!.seq, 7);
    await n.answer(0);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.cleared);
  });

  test('last heart lost → failed', () async {
    api.nexts.add(fakeEncounter(1));
    api.answers.add(fakeResult(correct: false, hearts: 0, failed: true));
    await n.encounter();
    await n.answer(2);
    expect(n.state.hearts, 0);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.failed);
  });

  test('double tap sends one answer', () async {
    api.nexts.add(fakeEncounter(1));
    api.answers.add(fakeResult(kills: 1));
    api.answerGate = Completer<void>();
    await n.encounter();
    final first = n.answer(1);
    final second = n.answer(2);
    expect(n.state.phase, RunPhase.answering);
    expect(n.state.selectedIndex, 1);
    api.answerGate!.complete();
    await Future.wait([first, second]);
    expect(api.answerCalls, 1);
    await n.answer(3); // 결과가 나온 뒤에도 무시
    expect(api.answerCalls, 1);
  });

  test('RUN_EXPIRED on next → expired, on answer → ended', () async {
    api.nexts.add(GameApiException(GameApiException.runExpired));
    expect(await n.encounter(), isFalse);
    expect(n.state.phase, RunPhase.expired);

    await n.start();
    api.nexts.add(fakeEncounter(1));
    api.answers.add(GameApiException(GameApiException.runExpired));
    await n.encounter();
    await n.answer(0);
    expect(n.state.phase, RunPhase.ended);
  });

  test('offline encounter → notice, keep exploring', () async {
    api.nexts.add(GameApiException(GameApiException.offline));
    expect(await n.encounter(), isFalse);
    expect(n.state.phase, RunPhase.exploring);
    expect(n.state.notice, isNotNull);
    n.clearNotice();
    expect(n.state.notice, isNull);
  });

  test('ANSWER_IN_PROGRESS retries once', () async {
    api.nexts.add(fakeEncounter(1));
    api.answers.addAll([
      GameApiException(GameApiException.answerInProgress),
      fakeResult(kills: 1),
    ]);
    await n.encounter();
    await n.answer(0);
    expect(api.answerCalls, 2);
    expect(n.state.result, isNotNull);
  });

  test('SEQ_MISMATCH refetches next and lets the user pick again', () async {
    api.nexts.addAll([fakeEncounter(1), fakeEncounter(2)]);
    api.answers.add(GameApiException(GameApiException.seqMismatch));
    await n.encounter();
    await n.answer(0);
    expect(n.state.phase, RunPhase.battle);
    expect(n.state.encounter!.seq, 2);
    expect(n.state.selectedIndex, isNull);
    expect(n.state.result, isNull);
  });

  test('GAME_UNAVAILABLE on answer → notice, can answer again', () async {
    api.nexts.add(fakeEncounter(1));
    api.answers.addAll([
      GameApiException(GameApiException.unavailable),
      fakeResult(kills: 1),
    ]);
    await n.encounter();
    await n.answer(0);
    expect(n.state.phase, RunPhase.battle);
    expect(n.state.notice, isNotNull);
    expect(n.state.selectedIndex, isNull);
    await n.answer(0);
    expect(n.state.result, isNotNull);
  });

  test('DUNGEON_CLOSED on start → closed', () async {
    api.startResult = GameApiException(GameApiException.dungeonClosed);
    await n.start();
    expect(n.state.phase, RunPhase.closed);
  });

  test('start and answers track hp/maxHp', () async {
    api.startResult = const RunStart(
        runId: 'r', hearts: 3, kills: 0, killsToBoss: 3, hp: 100, maxHp: 100);
    await n.start();
    expect(n.state.hp, 100);
    expect(n.state.maxHp, 100);
    expect(n.state.killsToBoss, 3);
    api.nexts.add(fakeEncounter(1));
    api.answers.add(fakeResult(
        correct: false, hp: 75, damage: 25, monsterHp: 30, hearts: 3));
    await n.encounter();
    await n.answer(1);
    expect(n.state.hp, 75);
  });

  test('monster not defeated → next question with the same monster', () async {
    api.nexts.addAll([
      fakeEncounter(1),
      fakeEncounter(2, hp: 15),
    ]);
    api.answers.addAll([
      fakeResult(damage: 15, monsterHp: 15, monsterDefeated: false),
      fakeResult(kills: 1, damage: 15, monsterHp: 0, monsterDefeated: true),
    ]);
    await n.encounter();
    await n.answer(0);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.battle);
    expect(n.state.encounter!.seq, 2);
    expect(n.state.encounter!.monster.hp, 15);
    expect(n.state.result, isNull);
    await n.answer(0);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.exploring);
    expect(n.state.kills, 1);
  });

  test('wrong answer on an old server (no monsterDefeated) chains too',
      () async {
    api.nexts.addAll([fakeEncounter(1), fakeEncounter(2)]);
    api.answers.add(fakeResult(correct: false, hearts: 2));
    await n.encounter();
    await n.answer(1);
    await n.finishBattle();
    expect(n.state.phase, RunPhase.battle);
    expect(n.state.encounter!.seq, 2);
  });

  group('review run', () {
    late RunNotifier rv;
    setUp(() {
      rv = RunNotifier(api, '',
          retryDelay: Duration.zero, startRun: () => api.startReview(7));
    });

    test('uses the injected start and chains all questions until cleared',
        () async {
      api.nexts.addAll([fakeEncounter(1), fakeEncounter(2)]);
      api.answers.addAll([
        fakeResult(
            review: true,
            reviewCorrect: 1,
            reviewTotal: 2,
            monsterDefeated: false,
            monsterHp: 15),
        fakeResult(
            correct: false,
            review: true,
            reviewCorrect: 1,
            reviewTotal: 2,
            cleared: true,
            monsterDefeated: false,
            monsterHp: 15),
      ]);
      await rv.start();
      expect(api.reviewCalls, [7]);
      expect(rv.state.review, isTrue);
      expect(rv.state.reviewTotal, 2);
      expect(rv.state.reviewAnswered, 0);
      expect(await rv.encounter(), isTrue);
      await rv.answer(0);
      expect(rv.state.reviewAnswered, 1);
      await rv.finishBattle();
      expect(rv.state.phase, RunPhase.battle);
      expect(rv.state.encounter!.seq, 2);
      await rv.answer(1);
      expect(rv.state.reviewAnswered, 2);
      await rv.finishBattle();
      expect(rv.state.phase, RunPhase.cleared);
      expect(rv.state.result!.reviewCorrect, 1);
    });

    test('correct but defeated mid-review still chains', () async {
      api.nexts.addAll([fakeEncounter(1), fakeEncounter(2)]);
      api.answers.add(fakeResult(review: true, monsterDefeated: true));
      await rv.start();
      await rv.encounter();
      await rv.answer(0);
      await rv.finishBattle();
      expect(rv.state.phase, RunPhase.battle);
    });

    test('NOT_CAUGHT on start → error with a message', () async {
      api.reviewStartResult =
          GameApiException(GameApiException.notCaught, statusCode: 409);
      await rv.start();
      expect(rv.state.phase, RunPhase.error);
      expect(rv.state.notice, contains('잡지 못한'));
    });
  });
}
