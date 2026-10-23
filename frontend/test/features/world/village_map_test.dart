import 'package:gossiplog/features/world/game/top_down_grid.dart';
import 'package:gossiplog/features/world/game/village_map.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const grid = TopDownGrid();
  const ids = ['k8s', 'jvm', 'db', 'net', 'build', 'ops', 'ai', 'shell'];

  test('is a 24x24 grid using only the legend characters', () {
    expect(VillageMap.rows.length, 24);
    for (final row in VillageMap.rows) {
      expect(row.length, 24);
      expect(RegExp(r'^[GPWTBDFS]+$').hasMatch(row), isTrue, reason: row);
    }
  });

  test('has one door per dungeon, each on a D tile', () {
    expect(VillageMap.doors.keys.toSet(), ids.toSet());
    for (final MapEntry(key: id, value: (c, r)) in VillageMap.doors.entries) {
      expect(VillageMap.tileAt(c, r), 'D', reason: id);
      expect(VillageMap.isWalkable(c, r), isTrue, reason: id);
    }
    final dCount = VillageMap.rows.join().split('').where((t) => t == 'D');
    expect(dCount.length, 8);
  });

  test('every door touches a walkable non-door tile', () {
    for (final MapEntry(key: id, value: (c, r)) in VillageMap.doors.entries) {
      final neighbours = [(c + 1, r), (c - 1, r), (c, r + 1), (c, r - 1)];
      final ok = neighbours.any((n) =>
          VillageMap.isWalkable(n.$1, n.$2) &&
          VillageMap.tileAt(n.$1, n.$2) != 'D');
      expect(ok, isTrue, reason: id);
    }
  });

  test('start tile is S and every door is reachable from it', () {
    final (sc, sr) = VillageMap.startTile;
    expect(VillageMap.tileAt(sc, sr), 'S');
    for (final MapEntry(key: id, value: door) in VillageMap.doors.entries) {
      final path =
          grid.findPath(VillageMap.startTile, door, VillageMap.isWalkable);
      expect(path, isNotEmpty, reason: id);
      expect(path.last, door);
    }
  });

  test('each building footprint is B tiles with its door just in front', () {
    for (final id in ids) {
      final (c0, r0) = VillageMap.buildings[id]!;
      for (var dc = 0; dc < VillageMap.footprint; dc++) {
        for (var dr = 0; dr < VillageMap.footprint; dr++) {
          expect(VillageMap.tileAt(c0 + dc, r0 + dr), 'B', reason: id);
        }
      }
      final (dcol, drow) = VillageMap.doors[id]!;
      expect(drow, r0 + VillageMap.footprint, reason: id);
      expect(dcol, inInclusiveRange(c0, c0 + VillageMap.footprint - 1),
          reason: id);
    }
  });

  test('the fountain blocks the 3x3 around its tile but not the plaza ring',
      () {
    final (fc, fr) = VillageMap.fountainTile;
    for (var dc = -1; dc <= 1; dc++) {
      for (var dr = -1; dr <= 1; dr++) {
        expect(VillageMap.tileAt(fc + dc, fr + dr), 'F');
        expect(VillageMap.isWalkable(fc + dc, fr + dr), isFalse);
      }
    }
    expect(VillageMap.rows.join().split('').where((t) => t == 'F').length, 9);
    // 분수를 둘러싼 광장 칸은 그대로 걸을 수 있다.
    for (final (c, r) in [
      (fc - 2, fr),
      (fc + 2, fr),
      (fc, fr - 2),
      (fc, fr + 2)
    ]) {
      expect(VillageMap.isWalkable(c, r), isTrue, reason: '($c,$r)');
    }
  });

  test('out-of-bounds tiles are not walkable', () {
    expect(VillageMap.isWalkable(-1, 0), isFalse);
    expect(VillageMap.isWalkable(0, 24), isFalse);
  });
}
