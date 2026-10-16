int _int(Object? v, [int fallback = 0]) => v is num ? v.toInt() : fallback;
bool _bool(Object? v) => v == true;
String _str(Object? v) => v?.toString() ?? '';

class DungeonInfo {
  final String id;
  final String name;
  final String buildingStyle;
  final bool open;
  final bool clearedByMe;
  final int clearCount;

  const DungeonInfo({
    required this.id,
    required this.name,
    required this.buildingStyle,
    required this.open,
    required this.clearedByMe,
    required this.clearCount,
  });

  factory DungeonInfo.fromJson(Map<String, dynamic> j) => DungeonInfo(
        id: _str(j['id']),
        name: _str(j['name']),
        buildingStyle: _str(j['buildingStyle']),
        open: _bool(j['open']),
        clearedByMe: _bool(j['clearedByMe']),
        clearCount: _int(j['clearCount']),
      );
}

class GameMe {
  final int clearedCount;
  final int total;
  final bool graduated;

  /// 위키 던전 문제 검수 관리자. 옛 서버는 보내지 않는다 → false.
  final bool admin;

  const GameMe(
      {required this.clearedCount,
      required this.total,
      required this.graduated,
      this.admin = false});

  factory GameMe.fromJson(Map<String, dynamic> j) => GameMe(
        clearedCount: _int(j['clearedCount']),
        total: _int(j['total'], 8),
        graduated: _bool(j['graduated']),
        admin: _bool(j['admin']),
      );
}

class RunStart {
  final String runId;
  final int hearts;
  final int kills;
  final int killsToBoss;

  const RunStart(
      {required this.runId,
      required this.hearts,
      required this.kills,
      required this.killsToBoss});

  factory RunStart.fromJson(Map<String, dynamic> j) => RunStart(
        runId: _str(j['runId']),
        hearts: _int(j['hearts'], 3),
        kills: _int(j['kills']),
        killsToBoss: _int(j['killsToBoss'], 5),
      );
}

class MonsterInfo {
  final String name;
  final String spriteKey;
  final bool isBoss;

  const MonsterInfo(
      {required this.name, required this.spriteKey, required this.isBoss});

  factory MonsterInfo.fromJson(Map<String, dynamic> j) => MonsterInfo(
        name: _str(j['name']),
        spriteKey: _str(j['spriteKey']),
        isBoss: _bool(j['isBoss']),
      );
}

class BattleQuestion {
  final int id;
  final String stem;
  final List<String> choices;
  final String topicTitle;

  const BattleQuestion(
      {required this.id,
      required this.stem,
      required this.choices,
      required this.topicTitle});

  factory BattleQuestion.fromJson(Map<String, dynamic> j) => BattleQuestion(
        id: _int(j['id']),
        stem: _str(j['stem']),
        choices: (j['choices'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(),
        topicTitle: _str(j['topicTitle']),
      );
}

class Encounter {
  final int seq;
  final MonsterInfo monster;
  final BattleQuestion question;

  const Encounter(
      {required this.seq, required this.monster, required this.question});

  factory Encounter.fromJson(Map<String, dynamic> j) => Encounter(
        seq: _int(j['seq']),
        monster: MonsterInfo.fromJson(
            Map<String, dynamic>.from(j['monster'] as Map)),
        question: BattleQuestion.fromJson(
            Map<String, dynamic>.from(j['question'] as Map)),
      );
}

class AnswerResult {
  final bool correct;
  final int correctIndex;
  final String explanation;
  final String topicTitle;
  final String? sourceRef;
  final int hearts;
  final int kills;
  final bool bossUnlocked;
  final bool cleared;
  final bool failed;

  const AnswerResult({
    required this.correct,
    required this.correctIndex,
    required this.explanation,
    required this.topicTitle,
    this.sourceRef,
    required this.hearts,
    required this.kills,
    required this.bossUnlocked,
    required this.cleared,
    required this.failed,
  });

  factory AnswerResult.fromJson(Map<String, dynamic> j) => AnswerResult(
        correct: _bool(j['correct']),
        correctIndex: _int(j['correctIndex'], -1),
        explanation: _str(j['explanation']),
        topicTitle: _str(j['topicTitle']),
        sourceRef: j['sourceRef']?.toString(),
        hearts: _int(j['hearts']),
        kills: _int(j['kills']),
        bossUnlocked: _bool(j['bossUnlocked']),
        cleared: _bool(j['cleared']),
        failed: _bool(j['failed']),
      );
}

class LeaderboardEntry {
  final String username;
  final int clearedCount;
  final int correctCount;

  const LeaderboardEntry(
      {required this.username,
      required this.clearedCount,
      required this.correctCount});

  factory LeaderboardEntry.fromJson(Map<String, dynamic> j) => LeaderboardEntry(
        username: _str(j['username']),
        clearedCount: _int(j['clearedCount']),
        correctCount: _int(j['correctCount']),
      );
}

/// 마을 화면이 쓰는 묶음: 던전 목록과 내 진행도.
class WorldData {
  final List<DungeonInfo> dungeons;
  final GameMe me;

  const WorldData({required this.dungeons, required this.me});
}
