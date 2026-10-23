import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/features/world/battle/battle_overlay.dart';
import 'package:gossiplog/features/world/game/student_avatar.dart';
import 'package:gossiplog/features/world/game/village_map.dart';
import 'package:gossiplog/features/world/state/game_models.dart';

const _ids = ['k8s', 'jvm', 'db', 'net', 'build', 'ops', 'ai', 'shell'];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('every world asset the code builds is bundled', () async {
    final paths = <String>{
      for (final look in StudentLook.values) ...[
        studentBackAsset(look),
        for (final f in StudentFacing.values)
          'assets/world/student_${look.name}_${f.name}.svg',
      ],
      'assets/world/student_cap.svg',
      for (final n in ['tree', 'fountain', 'lamp', 'flag'])
        'assets/world/$n.svg',
      for (final id in VillageMap.buildings.keys)
        'assets/world/building_$id.svg',
      for (final id in _ids) ...[
        'assets/world/building_$id.svg',
        monsterAsset(
            MonsterInfo(name: 'm', spriteKey: 'monster_$id', isBoss: false),
            id),
        monsterAsset(
            MonsterInfo(name: 'b', spriteKey: 'boss_$id', isBoss: true), id),
      ],
    };
    for (final p in paths) {
      expect((await rootBundle.load(p)).lengthInBytes, greaterThan(0),
          reason: p);
    }
  });
}
