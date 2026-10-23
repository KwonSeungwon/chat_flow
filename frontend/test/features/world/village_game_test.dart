import 'package:gossiplog/features/world/game/student_avatar.dart';
import 'package:gossiplog/features/world/game/village_game.dart';
import 'package:gossiplog/features/world/game/village_map.dart';
import 'package:gossiplog/features/world/game/world_sprite.dart';
import 'package:gossiplog/features/world/state/game_models.dart';
import 'package:flame/game.dart';
import 'package:flame_test/flame_test.dart';
import 'package:flutter/widgets.dart';
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

  void settle(VillageGame game) {
    for (var i = 0; i < 2000 && game.avatar.isMoving; i++) {
      game.update(1 / 30);
    }
  }

  void tapTile(VillageGame game, (int, int) t) =>
      game.world.onTap!(VillageGame.grid.toScreen(t.$1, t.$2));

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

  testWithGame<VillageGame>(
    'a tapped route never passes through another building door',
    () => VillageGame(onDoorReached: reached.add),
    (game) async {
      reached.clear();
      await game.ready();
      // (10,6)과 (12,6) 사이에 JVM 대장간 문(11,6)이 있다. 문을 지나는 길이
      // 최단(2칸)이라 문을 막지 않으면 엉뚱하게 jvm에 들어간다.
      game.avatar.placeAt((10, 6));
      tapTile(game, (12, 6));
      settle(game);
      expect(game.avatar.tile, (12, 6));
      expect(reached, isEmpty);

      // 문 자체를 누르면 그 문으로는 간다.
      tapTile(game, VillageMap.doors['k8s']!);
      settle(game);
      expect(reached, ['k8s']);
    },
  );

  testWithGame<VillageGame>(
    'a held key that the keyboard no longer reports stops moving the avatar',
    () => VillageGame(),
    (game) async {
      await game.ready();
      var pressed = <LogicalKeyboardKey>{LogicalKeyboardKey.arrowRight};
      game.pressedKeys = () => pressed;
      game.onKeyEvent(
        const KeyDownEvent(
          physicalKey: PhysicalKeyboardKey.arrowRight,
          logicalKey: LogicalKeyboardKey.arrowRight,
          timeStamp: Duration.zero,
        ),
        pressed,
      );
      game.update(1 / 30);
      expect(game.avatar.isMoving, isTrue);
      settle(game);
      final stoppedAt = game.avatar.tile;
      expect(stoppedAt, isNot(VillageMap.startTile));

      // 포커스가 떠나 KeyUp을 못 받은 채 키가 떼어졌다.
      pressed = {};
      for (var i = 0; i < 60; i++) {
        game.update(1 / 30);
      }
      settle(game);
      final after = game.avatar.tile;
      expect((after.$1 - stoppedAt.$1).abs(), lessThanOrEqualTo(1));
      pressed = {LogicalKeyboardKey.arrowRight};
      for (var i = 0; i < 60; i++) {
        game.update(1 / 30);
      }
      // 같은 키가 다시 보고돼도 새 KeyDown 없이는 걷지 않는다.
      expect(game.avatar.tile, after);
    },
  );

  testWithGame<VillageGame>(
    'reaching a door with a key held clears the held direction',
    () => VillageGame(onDoorReached: reached.add),
    (game) async {
      reached.clear();
      await game.ready();
      game.avatar.placeAt((4, 7));
      final pressed = {LogicalKeyboardKey.keyW};
      game.pressedKeys = () => pressed;
      game.onKeyEvent(
        const KeyDownEvent(
          physicalKey: PhysicalKeyboardKey.keyW,
          logicalKey: LogicalKeyboardKey.keyW,
          timeStamp: Duration.zero,
        ),
        pressed,
      );
      for (var i = 0; i < 120; i++) {
        game.update(1 / 30);
      }
      expect(reached, ['k8s']);
      expect(game.avatar.tile, VillageMap.doors['k8s']);
      expect(game.avatar.isMoving, isFalse);
    },
  );

  test('KeyUp is handled only for movement keys', () {
    final game = VillageGame();
    KeyUpEvent up(LogicalKeyboardKey k, PhysicalKeyboardKey p) =>
        KeyUpEvent(physicalKey: p, logicalKey: k, timeStamp: Duration.zero);
    expect(
        game.onKeyEvent(
            up(LogicalKeyboardKey.keyA, PhysicalKeyboardKey.keyA), {}),
        KeyEventResult.handled);
    expect(
        game.onKeyEvent(
            up(LogicalKeyboardKey.enter, PhysicalKeyboardKey.enter), {}),
        KeyEventResult.ignored);
  });

  testWithGame<VillageGame>(
    'tapping the fountain or a tile next to it fires onFountainTapped',
    () => VillageGame(onDoorReached: reached.add),
    (game) async {
      reached.clear();
      await game.ready();
      var taps = 0;
      game.onFountainTapped = () => taps++;
      final (fc, fr) = VillageMap.fountainTile;
      tapTile(game, (fc, fr));
      tapTile(game, (fc + 1, fr + 1));
      tapTile(game, (fc - 1, fr));
      expect(taps, 3);
      expect(game.avatar.isMoving, isFalse);
      tapTile(game, (fc + 2, fr));
      expect(taps, 3);
    },
  );

  testWithGame<VillageGame>(
    'the chosen look reaches the avatar',
    () => VillageGame(look: StudentLook.girl),
    (game) async {
      await game.ready();
      expect(game.avatar.look, StudentLook.girl);
      expect(VillageGame().look, StudentLook.boy);
    },
  );

  testWithGame<VillageGame>(
    'camera follows the avatar but stops at the map edge',
    () => VillageGame(),
    (game) async {
      await game.ready();
      Rect visible() => game.camera.visibleWorldRect;
      final extent = VillageGame.mapExtent;

      game.update(1 / 30);
      expect(visible().center.dx, closeTo(game.avatar.position.x, 0.01));
      expect(visible().center.dy, closeTo(game.avatar.position.y, 0.01));

      // 왼쪽 위 구석: 맵 밖이 보이지 않는다.
      game.avatar.placeAt((1, 1));
      game.update(1 / 30);
      expect(visible().left, closeTo(0, 0.01));
      expect(visible().top, closeTo(0, 0.01));

      game.avatar.placeAt((22, 22));
      game.update(1 / 30);
      expect(visible().right, closeTo(extent, 0.01));
      expect(visible().bottom, closeTo(extent, 0.01));
    },
  );

  test('about 12 tiles across on a desktop, 7 on a phone, 7 rows at least', () {
    (double, double) visibleTiles(double w, double h) {
      final game = VillageGame()..onGameResize(Vector2(w, h));
      final z = game.camera.viewfinder.zoom * VillageGame.grid.tile;
      return (w / z, h / z);
    }

    expect(visibleTiles(1280, 800).$1, closeTo(12, 0.01));
    expect(visibleTiles(390, 844).$1, closeTo(7, 0.01));
    // 가로로 누운 폰: 세로 7칸을 지키고 가로는 더 넓게 보인다.
    final (cols, rows) = visibleTiles(844, 390);
    expect(rows, closeTo(VillageGame.minRows, 0.01));
    expect(cols, greaterThan(12));
  });

  testWithGame<VillageGame>(
    'depth: behind a building from the row above, in front on its door',
    () => VillageGame(),
    (game) async {
      await game.ready();
      final jvm = game.depthOf('jvm');
      final (c0, r0) = VillageMap.buildings['jvm']!;
      game.avatar.placeAt((c0 + 1, r0 - 1));
      expect(game.avatar.priority, lessThan(jvm.building));
      game.avatar.placeAt(VillageMap.doors['jvm']!);
      expect(game.avatar.priority, greaterThan(jvm.building));
    },
  );

  testWithGame<VillageGame>(
    'depth: a name chip sits over the tree above it, under a student there',
    () => VillageGame(),
    (game) async {
      await game.ready();
      // 네트워크 요새(3,10) 바로 위 줄의 (5,9)에 나무가 있고, (4,9)는 잔디다.
      final net = game.depthOf('net');
      const t = 48.0;
      final tree = game.world.children
          .whereType<WorldSprite>()
          .singleWhere((s) => s.position == Vector2(5.5 * t, 10 * t));
      game.avatar.placeAt((4, 9));
      expect(net.label, greaterThan(tree.priority));
      expect(net.label, lessThan(game.avatar.priority));
      expect(net.label, lessThan(net.building));
    },
  );

  testWithGame<VillageGame>(
    'the avatar faces the way it walks; left mirrors the side sprite',
    () => VillageGame(),
    (game) async {
      await game.ready();
      void step((int, int) d) {
        final (c, r) = game.avatar.tile;
        game.avatar.moveAlong([(c + d.$1, r + d.$2)]);
        game.update(1 / 60);
      }

      expect(game.avatar.facing, StudentFacing.down);
      step((0, -1));
      expect(game.avatar.facing, StudentFacing.up);
      settle(game);
      step((1, 0));
      expect(game.avatar.facing, StudentFacing.side);
      expect(game.avatar.mirrored, isFalse);
      settle(game);
      step((-1, 0));
      expect(game.avatar.facing, StudentFacing.side);
      expect(game.avatar.mirrored, isTrue);
      settle(game);
      step((0, 1));
      expect(game.avatar.facing, StudentFacing.down);
      expect(game.avatar.mirrored, isFalse);
      // 멈춰도 마지막 방향을 유지한다.
      settle(game);
      expect(game.avatar.facing, StudentFacing.down);
    },
  );

  testWidgets('attaching and disposing games repeatedly releases them',
      (tester) async {
    for (var i = 0; i < 3; i++) {
      final game = VillageGame();
      await tester.pumpWidget(GameWidget(game: game));
      bool ready() => game.isLoaded && game.avatar.isLoaded;
      for (var f = 0; f < 40 && !ready(); f++) {
        await tester.runAsync(
            () => Future<void>.delayed(const Duration(milliseconds: 50)));
        await tester.pump(const Duration(milliseconds: 16));
      }
      await tester.pump(const Duration(milliseconds: 16));
      expect(ready(), isTrue);
      final avatar = game.avatar;
      expect(avatar.assetsReleased, isFalse);
      await tester.pumpWidget(const SizedBox());
      // GameWidget.dispose → VillageGame.onDispose: 그림과 SVG를 놓는다.
      expect(game.camera.isRemoved, isTrue);
      expect(avatar.assetsReleased, isTrue);
    }
  });

  testWidgets('SvgBank disposed mid-load drops late SVGs', (tester) async {
    await tester.runAsync(() async {
      final bank = SvgBank();
      final load = bank.loadAll(FlameGame(), ['tree', 'fountain']);
      bank.dispose(); // game left before the reads finished
      await load;
      expect(bank.length, 0);
      expect(bank.isDisposed, isTrue);
    });
  });
}
