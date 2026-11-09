int _int(Object? v, [int fallback = 0]) => v is num ? v.toInt() : fallback;
bool _bool(Object? v) => v == true;
String _str(Object? v) => v?.toString() ?? '';

/// 내 최대 HP. 옛 서버는 HP를 보내지 않는다.
const defaultMaxHp = 100;

/// 옛 서버의 하트(최대 3)를 HP로 바꾼다.
int hpFromHearts(int hearts, [int maxHp = defaultMaxHp]) =>
    (hearts * maxHp / 3).round().clamp(0, maxHp);

/// hp가 없거나, 배포 전에 저장된 결과라 maxHp가 0으로 오면 hearts로 환산한다.
int _maxHp(Map<String, dynamic> j) {
  final v = _int(j['maxHp'], defaultMaxHp);
  return v > 0 ? v : defaultMaxHp;
}

int _hp(Map<String, dynamic> j, int maxHp) =>
    j['hp'] is num && _int(j['maxHp'], defaultMaxHp) > 0
        ? _int(j['hp'])
        : hpFromHearts(_int(j['hearts'], 3), maxHp);

/// 주인공 그림(남·여 학생).
enum StudentLook { boy, girl }

/// 서버의 `avatar` 값. 없거나 모르는 값이면 null(아직 안 고름).
StudentLook? studentLookFromJson(Object? v) => switch (v) {
      'boy' => StudentLook.boy,
      'girl' => StudentLook.girl,
      _ => null,
    };

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

  /// 고른 주인공 그림. 아직 안 골랐거나 옛 서버면 null.
  final StudentLook? avatar;

  const GameMe(
      {required this.clearedCount,
      required this.total,
      required this.graduated,
      this.admin = false,
      this.avatar});

  factory GameMe.fromJson(Map<String, dynamic> j) => GameMe(
        clearedCount: _int(j['clearedCount']),
        total: _int(j['total'], 8),
        graduated: _bool(j['graduated']),
        admin: _bool(j['admin']),
        avatar: studentLookFromJson(j['avatar']),
      );
}

class RunStart {
  final String runId;
  final int hearts;
  final int kills;
  final int killsToBoss;
  final int hp;
  final int maxHp;

  /// 도감 복습 판이다(실패 없음, [reviewTotal]문제를 다 풀면 끝).
  final bool review;
  final int reviewTotal;

  const RunStart(
      {required this.runId,
      required this.hearts,
      required this.kills,
      required this.killsToBoss,
      this.hp = defaultMaxHp,
      this.maxHp = defaultMaxHp,
      this.review = false,
      this.reviewTotal = 0});

  factory RunStart.fromJson(Map<String, dynamic> j) {
    final maxHp = _maxHp(j);
    return RunStart(
      runId: _str(j['runId']),
      hearts: _int(j['hearts'], 3),
      kills: _int(j['kills']),
      killsToBoss: _int(j['killsToBoss'], 5),
      hp: _hp(j, maxHp),
      maxHp: maxHp,
      review: _bool(j['review']),
      reviewTotal: _int(j['reviewTotal']),
    );
  }
}

class MonsterInfo {
  final String name;
  final String spriteKey;
  final bool isBoss;

  /// 답하기 전 몬스터 HP. 옛 서버는 보내지 않는다 → 1/1(가득).
  final int hp;
  final int maxHp;

  const MonsterInfo(
      {required this.name,
      required this.spriteKey,
      required this.isBoss,
      this.hp = 1,
      this.maxHp = 1});

  factory MonsterInfo.fromJson(Map<String, dynamic> j) => MonsterInfo(
        name: _str(j['name']),
        spriteKey: _str(j['spriteKey']),
        isBoss: _bool(j['isBoss']),
        hp: _int(j['hp'], 1),
        maxHp: _int(j['maxHp'], 1),
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
  final int hp;
  final int maxHp;

  /// 정답이면 몬스터에게 준 피해, 오답이면 내가 받은 피해. 옛 서버는 0.
  final int damage;
  final bool critical;
  final int monsterMaxHp;
  final int? _monsterHp;
  final bool? _monsterDefeated;
  final bool review;
  final int reviewCorrect;
  final int reviewTotal;

  /// 방금 싸운 몬스터를 쓰러뜨렸다. 옛 서버는 정답이면 쓰러뜨린 것으로 본다.
  bool get monsterDefeated => _monsterDefeated ?? correct;

  /// 방금 싸운 몬스터의 남은 HP(쓰러뜨렸으면 0).
  int get monsterHp => _monsterHp ?? (monsterDefeated ? 0 : monsterMaxHp);

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
    this.hp = defaultMaxHp,
    this.maxHp = defaultMaxHp,
    this.damage = 0,
    this.critical = false,
    this.monsterMaxHp = 1,
    int? monsterHp,
    bool? monsterDefeated,
    this.review = false,
    this.reviewCorrect = 0,
    this.reviewTotal = 0,
  })  : _monsterHp = monsterHp,
        _monsterDefeated = monsterDefeated;

  factory AnswerResult.fromJson(Map<String, dynamic> j) {
    final maxHp = _maxHp(j);
    return AnswerResult(
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
      hp: _hp(j, maxHp),
      maxHp: maxHp,
      damage: _int(j['damage']),
      critical: _bool(j['critical']),
      monsterMaxHp: _int(j['monsterMaxHp'], 1) > 0 ? _int(j['monsterMaxHp'], 1) : 1,
      monsterHp: j['monsterHp'] is num ? _int(j['monsterHp']) : null,
      monsterDefeated:
          j['monsterDefeated'] is bool ? j['monsterDefeated'] as bool : null,
      review: _bool(j['review']),
      reviewCorrect: _int(j['reviewCorrect']),
      reviewTotal: _int(j['reviewTotal']),
    );
  }
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

/// 도감 한 칸: 주제 하나 = 몬스터 하나. 정답을 한 번이라도 내면 잡은 것이다.
class CollectionEntry {
  final int topicId;
  final String dungeonId;
  final String dungeonName;
  final String topicTitle;

  /// 못 잡았으면 null.
  final String? monsterName;
  final String spriteKey;
  final bool caught;
  final int correctCount;
  final int questionCount;

  /// 복습 기한이 지난 문제 수.
  final int dueCount;

  const CollectionEntry({
    required this.topicId,
    required this.dungeonId,
    required this.dungeonName,
    required this.topicTitle,
    this.monsterName,
    required this.spriteKey,
    required this.caught,
    required this.correctCount,
    required this.questionCount,
    required this.dueCount,
  });

  factory CollectionEntry.fromJson(Map<String, dynamic> j) => CollectionEntry(
        topicId: _int(j['topicId']),
        dungeonId: _str(j['dungeonId']),
        dungeonName: _str(j['dungeonName']),
        topicTitle: _str(j['topicTitle']),
        monsterName: j['monsterName']?.toString(),
        spriteKey: _str(j['spriteKey']),
        caught: _bool(j['caught']),
        correctCount: _int(j['correctCount']),
        questionCount: _int(j['questionCount']),
        dueCount: _int(j['dueCount']),
      );
}

/// 마을 화면이 쓰는 묶음: 던전 목록과 내 진행도.
class WorldData {
  final List<DungeonInfo> dungeons;
  final GameMe me;

  const WorldData({required this.dungeons, required this.me});
}
