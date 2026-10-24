import 'dart:math';

import 'package:gossiplog/features/world/game/dungeon_game.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
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

  testWithGame<DungeonGame>(
    'the chosen look reaches the avatar',
    () => DungeonGame('k8s', look: StudentLook.girl),
    (game) async {
      await game.ready();
      expect(game.avatar.look, StudentLook.girl);
    },
  );

  testWithGame<DungeonGame>(
    'a pillar hides a student standing behind it and not one in front',
    () => DungeonGame('db'),
    (game) async {
      await game.ready();
      for (final (c, r) in DungeonGame.pillars) {
        game.avatar.placeAt((c, r - 1));
        expect(game.avatar.priority, lessThan(game.pillarDepth((c, r))));
        game.avatar.placeAt((c, r + 1));
        expect(game.avatar.priority, greaterThan(game.pillarDepth((c, r))));
      }
    },
  );

  testWithGame<DungeonGame>(
    'camera stops at the room edge on a phone and centres a small room',
    () => DungeonGame('shell'),
    (game) async {
      await game.ready();
      final e = DungeonGame.roomExtent;
      for (final (w, h) in [(390.0, 844.0), (844.0, 390.0)]) {
        game.onGameResize(Vector2(w, h));
        for (final t in [(0, 0), (11, 0), (0, 11), (11, 11)]) {
          game.avatar.placeAt(t);
          game.update(0);
          final view = game.camera.visibleWorldRect;
          if (view.width <= e.width) {
            expect(view.left, greaterThanOrEqualTo(e.left - 1e-3));
            expect(view.right, lessThanOrEqualTo(e.right + 1e-3));
          } else {
            expect(view.center.dx, closeTo(e.center.dx, 1e-3));
          }
          if (view.height <= e.height) {
            expect(view.top, greaterThanOrEqualTo(e.top - 1e-3));
            expect(view.bottom, lessThanOrEqualTo(e.bottom + 1e-3));
          } else {
            expect(view.center.dy, closeTo(e.center.dy, 1e-3));
          }
        }
      }
      // 넓은 화면: 방보다 넓은 축은 가운데에 고정된다.
      game.onGameResize(Vector2(1920, 1080));
      game.avatar.placeAt((0, 0));
      game.update(0);
      final view = game.camera.visibleWorldRect;
      expect(view.width, greaterThan(e.width));
      expect(view.center.dx, closeTo(e.center.dx, 1e-3));
    },
  );

  test('tiles: 7 on the short side of a phone, 76px on big screens', () {
    (double, double) visibleTiles(double w, double h) {
      final game = DungeonGame('shell')..onGameResize(Vector2(w, h));
      final z = game.camera.viewfinder.zoom * DungeonGame.grid.tile;
      return (w / z, h / z);
    }

    expect(visibleTiles(390, 844).$1, closeTo(7, 0.01));
    expect(visibleTiles(844, 390).$2, closeTo(7, 0.01));
    expect(visibleTiles(1280, 800).$1, closeTo(1280 / 76, 0.01));
    expect(visibleTiles(1920, 1080).$2, closeTo(1080 / 76, 0.01));
  });

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
