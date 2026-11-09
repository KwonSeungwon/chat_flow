import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('배포 전에 저장돼 hp·maxHp가 0으로 오는 결과는 hearts로 HP를 환산한다', () {
    final r = AnswerResult.fromJson({
      'correct': false,
      'correctIndex': 1,
      'explanation': '',
      'topicTitle': 'Pod',
      'hearts': 2,
      'kills': 0,
      'bossUnlocked': false,
      'cleared': false,
      'failed': false,
      'hp': 0,
      'maxHp': 0,
      'monsterHp': 0,
      'monsterMaxHp': 0,
      'monsterDefeated': false,
    });
    expect(r.maxHp, 100);
    expect(r.hp, 67);
    expect(r.monsterMaxHp, greaterThan(0));
  });
}
