import 'package:gossiplog/features/world/game/iso_grid.dart';
import 'package:flame/components.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const grid = IsoGrid(tileW: 64, tileH: 32);

  group('toScreen / toTile', () {
    test('origin and unit steps follow the 2:1 diamond layout', () {
      expect(grid.toScreen(0, 0), Vector2(0, 0));
      expect(grid.toScreen(1, 0), Vector2(32, 16));
      expect(grid.toScreen(0, 1), Vector2(-32, 16));
      expect(grid.toScreen(3, 3), Vector2(0, 96));
    });

    test('round-trips every tile of a 24x24 map', () {
      for (var c = 0; c < 24; c++) {
        for (var r = 0; r < 24; r++) {
          expect(grid.toTile(grid.toScreen(c, r)), (c, r));
        }
      }
    });

    test('points inside the top rhombus map back to the same tile', () {
      final center = grid.toScreen(5, 7);
      for (final d in [
        Vector2(20, 0),
        Vector2(-20, 0),
        Vector2(0, 10),
        Vector2(0, -10),
        Vector2(10, 4),
      ]) {
        expect(grid.toTile(center + d), (5, 7), reason: 'offset $d');
      }
    });
  });

  group('findPath', () {
    bool inBounds(int c, int r) => c >= 0 && r >= 0 && c < 8 && r < 8;

    void expectContiguous(List<(int, int)> path) {
      for (var i = 1; i < path.length; i++) {
        final (c0, r0) = path[i - 1];
        final (c1, r1) = path[i];
        expect((c0 - c1).abs() + (r0 - r1).abs(), 1,
            reason: 'step $i must be a 4-way move');
      }
    }

    test('straight line on an open grid is the shortest path', () {
      final path = grid.findPath((0, 0), (4, 0), inBounds);
      expect(path.first, (0, 0));
      expect(path.last, (4, 0));
      expect(path.length, 5);
      expectContiguous(path);
    });

    test('start equal to goal returns a single tile', () {
      expect(grid.findPath((2, 2), (2, 2), inBounds), [(2, 2)]);
    });

    test('detours around a wall through its gap', () {
      // Wall on column 3 except row 7.
      bool walkable(int c, int r) => inBounds(c, r) && !(c == 3 && r != 7);
      final path = grid.findPath((0, 0), (6, 0), walkable);
      expect(path.first, (0, 0));
      expect(path.last, (6, 0));
      expect(path, contains((3, 7)));
      for (final (c, r) in path) {
        expect(walkable(c, r), isTrue);
      }
      expectContiguous(path);
      // Shortest detour: down 7, across 6, up 7.
      expect(path.length, 7 + 6 + 7 + 1);
    });

    test('returns an empty path when the goal is unreachable', () {
      bool walkable(int c, int r) => inBounds(c, r) && c != 3;
      expect(grid.findPath((0, 0), (6, 0), walkable), isEmpty);
    });

    test('returns an empty path when the goal itself is blocked', () {
      bool walkable(int c, int r) => inBounds(c, r) && !(c == 5 && r == 5);
      expect(grid.findPath((0, 0), (5, 5), walkable), isEmpty);
    });
  });
}
