import 'dart:async';

import 'package:gossiplog/features/world/state/game_api.dart';
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
}
