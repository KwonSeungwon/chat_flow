import 'package:chatflow/features/world/game/village_game.dart';
import 'package:chatflow/features/world/game/village_map.dart';
import 'package:flame_test/flame_test.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  void walk(VillageGame game, (int, int) goal) {
    final path = VillageGame.grid
        .findPath(game.avatar.tile, goal, VillageMap.isWalkable);
    expect(path, isNotEmpty);
    game.avatar.moveAlong(path);
    for (var i = 0; i < 2000 && game.avatar.isMoving; i++) {
      game.update(1 / 30);
    }
  }

  final reached = <String>[];

  testWithGame<VillageGame>(
    'walking onto an open door reports its dungeon id',
    () => VillageGame(onDoorReached: reached.add),
    (game) async {
      reached.clear();
      await game.ready();
      expect(game.avatar.tile, VillageMap.startTile);
      walk(game, VillageMap.doors['db']!);
      expect(game.avatar.tile, VillageMap.doors['db']);
      expect(reached, ['db']);
    },
  );

  testWithGame<VillageGame>(
    'a closed building does not open its door',
    () => VillageGame(onDoorReached: reached.add),
    (game) async {
      reached.clear();
      await game.ready();
      game.setDungeonState({
        'k8s': (open: false, cleared: false, clearCount: 0),
        'ai': (open: true, cleared: true, clearCount: 3),
      });
      walk(game, VillageMap.doors['k8s']!);
      expect(reached, isEmpty);
      walk(game, VillageMap.doors['ai']!);
      expect(reached, ['ai']);
    },
  );

  testWithGame<VillageGame>(
    'graduation and state set before load are applied on load',
    () => VillageGame()
      ..setGraduated(true)
      ..setDungeonState({'jvm': (open: true, cleared: true, clearCount: 7)}),
    (game) async {
      await game.ready();
      expect(game.avatar.graduated, isTrue);
      game.setGraduated(false);
      expect(game.avatar.graduated, isFalse);
    },
  );

  test('arrow keys and WASD map to the four grid directions', () {
    expect(directionFromKeys({LogicalKeyboardKey.arrowUp}), (0, -1));
    expect(directionFromKeys({LogicalKeyboardKey.keyS}), (0, 1));
    expect(directionFromKeys({LogicalKeyboardKey.keyA}), (-1, 0));
    expect(directionFromKeys({LogicalKeyboardKey.arrowRight}), (1, 0));
    expect(directionFromKeys({}), isNull);
  });
}
