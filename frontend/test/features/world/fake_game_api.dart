import 'dart:async';
import 'dart:collection';

import 'package:gossiplog/features/world/state/game_api.dart';
import 'package:gossiplog/features/world/state/game_models.dart';

Encounter fakeEncounter(int seq, {bool boss = false}) => Encounter(
      seq: seq,
      monster: MonsterInfo(
          name: boss ? '보스' : '슬라임',
          spriteKey: boss ? 'boss_k8s' : 'monster_k8s',
          isBoss: boss),
      question: BattleQuestion(
          id: seq, stem: 'Q$seq', choices: const ['a', 'b', 'c', 'd'], topicTitle: 't'),
    );

AnswerResult fakeResult({
  bool correct = true,
  int hearts = 3,
  int kills = 0,
  bool boss = false,
  bool cleared = false,
  bool failed = false,
}) =>
    AnswerResult(
      correct: correct,
      correctIndex: 0,
      explanation: 'e',
      topicTitle: 't',
      hearts: hearts,
      kills: kills,
      bossUnlocked: boss,
      cleared: cleared,
      failed: failed,
    );

/// 테스트용 가짜 API. 호출마다 큐에서 응답(값 또는 GameApiException)을 꺼낸다.
class FakeGameApi implements GameApi {
  final nexts = Queue<Object>();
  final answers = Queue<Object>();
  Object startResult = const RunStart(runId: 'r', hearts: 3, kills: 0, killsToBoss: 5);
  int answerCalls = 0;
  Completer<void>? answerGate;

  T _take<T>(Object v) {
    if (v is GameApiException) throw v;
    return v as T;
  }

  @override
  Future<RunStart> startRun(String dungeonId) async => _take(startResult);

  @override
  Future<Encounter> next(String runId) async => _take(nexts.removeFirst());

  @override
  Future<AnswerResult> answer(String runId, int seq, int choiceIndex) async {
    answerCalls++;
    await answerGate?.future;
    return _take(answers.removeFirst());
  }

  List<DungeonInfo> dungeons = const [];
  GameMe meResult = const GameMe(clearedCount: 0, total: 8, graduated: false);
  List<LeaderboardEntry> board = const [];

  @override
  Future<List<DungeonInfo>> listDungeons() async => dungeons;
  @override
  Future<GameMe> me() async => meResult;
  @override
  Future<List<LeaderboardEntry>> leaderboard() async => board;
}

