import 'dart:math';

import 'package:gossiplog/features/world/game/dungeon_game.dart';
import 'package:flame/game.dart';
import 'package:flame_test/flame_test.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  var encounters = 0;

  for (final seed in [1, 11, 2026]) {
    testWithGame<DungeonGame>(
      'seed $seed: first encounter comes after 6..12 steps and freezes the avatar',
      () => DungeonGame('net',
          onEncounter: () => encounters++, rng: Random(seed)),
      (game) async {
        encounters = 0;
        await game.ready();
        var steps = 0;
        var tile = game.avatar.tile;
        final row = DungeonGame.startTile.$2;
        game.avatar.moveAlong([
          for (var lap = 0; lap < 3; lap++) ...[
            for (var c = 7; c < 12; c++) (c, row),
            for (var c = 10; c >= 0; c--) (c, row),
            for (var c = 1; c <= 6; c++) (c, row),
          ],
        ]);
        for (var i = 0; i < 5000 && encounters == 0; i++) {
          game.update(1 / 30);
          if (game.avatar.tile != tile) {
            tile = game.avatar.tile;
            steps++;
          }
        }
        expect(encounters, 1);
        expect(steps, inInclusiveRange(6, 12));
        expect(game.isPaused, isTrue);

        // 배틀 중에는 남은 경로가 없고, 새 경로를 줘도 움직이지 않는다.
        for (var i = 0; i < 10; i++) {
          game.update(1 / 30);
        }
        expect(game.avatar.isMoving, isFalse);
        final before = game.avatar.position.clone();
        final (c, r) = game.avatar.tile;
        game.avatar.moveAlong([(c, r - 1)]);
        for (var i = 0; i < 30; i++) {
          game.update(1 / 30);
        }
        expect(game.avatar.position, before);

        game.resume();
        for (var i = 0; i < 30; i++) {
          game.update(1 / 30);
        }
        expect(game.avatar.tile, (c, r - 1));
        expect(game.isPaused, isFalse);
      },
    );
  }

  testWithGame<DungeonGame>(
    'pause() blocks movement and resume() releases it',
    () => DungeonGame('ai', rng: Random(5)),
    (game) async {
      await game.ready();
      game.pause();
      final before = game.avatar.position.clone();
      game.avatar.moveAlong([(6, 10)]);
      for (var i = 0; i < 30; i++) {
        game.update(1 / 30);
      }
      expect(game.avatar.position, before);
      game.resume();
      game.avatar.moveAlong([(6, 10)]);
      for (var i = 0; i < 30; i++) {
        game.update(1 / 30);
      }
      expect(game.avatar.tile, (6, 10));
    },
  );

  testWithGame<DungeonGame>(
    'boss phase toggles',
    () => DungeonGame('ops'),
    (game) async {
      await game.ready();
      expect(game.bossPhase, isFalse);
      game.setBossPhase(true);
      expect(game.bossPhase, isTrue);
      game.setBossPhase(false);
      expect(game.bossPhase, isFalse);
    },
  );

  test('pillars are not walkable and the start tile is', () {
    for (final (c, r) in DungeonGame.pillars) {
      expect(DungeonGame.isWalkable(c, r), isFalse);
    }
    final (sc, sr) = DungeonGame.startTile;
    expect(DungeonGame.isWalkable(sc, sr), isTrue);
    expect(DungeonGame.isWalkable(-1, 0), isFalse);
    expect(DungeonGame.isWalkable(0, DungeonGame.roomSize), isFalse);
  });

  testWidgets('attaching and disposing dungeons repeatedly releases them',
      (tester) async {
    for (final id in ['k8s', 'db', 'shell']) {
      final game = DungeonGame(id);
      await tester.pumpWidget(GameWidget(game: game));
      bool ready() => game.isLoaded && game.avatar.isLoaded;
      for (var f = 0; f < 40 && !ready(); f++) {
        await tester.runAsync(
            () => Future<void>.delayed(const Duration(milliseconds: 50)));
        await tester.pump(const Duration(milliseconds: 16));
      }
      await tester.pump(const Duration(milliseconds: 16));
      expect(ready(), isTrue);
      game.setBossPhase(true);
      final avatar = game.avatar;
      expect(avatar.assetsReleased, isFalse);
      await tester.pumpWidget(const SizedBox());
      expect(avatar.assetsReleased, isTrue);
    }
  });
}
