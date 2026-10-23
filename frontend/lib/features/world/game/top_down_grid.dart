import 'dart:collection';

import 'package:flame/components.dart';

/// 위에서 본 정사각 격자. 타일 한 칸이 [tile] 월드 px이고, (0,0) 칸의 왼쪽 위가
/// 월드 원점이다. 그리기 순서는 아래 변 y(클수록 앞)를 쓴다.
class TopDownGrid {
  const TopDownGrid({this.tile = 48});

  final double tile;

  /// 타일 중심.
  Vector2 toScreen(int col, int row) =>
      Vector2((col + 0.5) * tile, (row + 0.5) * tile);

  /// 화면 좌표가 들어 있는 타일.
  (int, int) toTile(Vector2 screen) =>
      ((screen.x / tile).floor(), (screen.y / tile).floor());

  /// 이웃한 두 칸 사이의 화면 거리.
  double get stepLength => tile;

  /// 발(아래 변)이 화면 y인 소품의 그리기 순서. 클수록 앞이다.
  static int depth(double y) => (y * 4).round();

  /// 주인공은 같은 y의 소품보다 살짝 앞에 그린다.
  int depthAt(double y) => depth(y) + 2;

  /// [start]에서 [goal]까지의 4방향 최단 경로(양 끝 포함).
  /// 도달할 수 없거나 [goal]이 막혀 있으면 빈 목록이다.
  List<(int, int)> findPath(
    (int, int) start,
    (int, int) goal,
    bool Function(int col, int row) walkable,
  ) {
    if (start == goal) return [start];
    if (!walkable(goal.$1, goal.$2)) return const [];

    int h((int, int) t) => (t.$1 - goal.$1).abs() + (t.$2 - goal.$2).abs();

    final gScore = <(int, int), int>{start: 0};
    final cameFrom = <(int, int), (int, int)>{};
    final closed = HashSet<(int, int)>();
    // (f, 삽입 순서, 타일). 같은 f면 먼저 넣은 쪽을 먼저 꺼낸다.
    var seq = 0;
    final open = SplayTreeSet<(int, int, (int, int))>((x, y) {
      final byF = x.$1.compareTo(y.$1);
      return byF != 0 ? byF : x.$2.compareTo(y.$2);
    })
      ..add((h(start), seq++, start));

    while (open.isNotEmpty && closed.length < _maxExpanded) {
      final current = open.first;
      open.remove(current);
      final tile = current.$3;
      if (tile == goal) return _rebuild(cameFrom, goal);
      if (!closed.add(tile)) continue;

      final g = gScore[tile]! + 1;
      for (final (dc, dr) in _dirs) {
        final next = (tile.$1 + dc, tile.$2 + dr);
        if (closed.contains(next) || !walkable(next.$1, next.$2)) continue;
        final known = gScore[next];
        if (known != null && known <= g) continue;
        gScore[next] = g;
        cameFrom[next] = tile;
        open.add((g + h(next), seq++, next));
      }
    }
    return const [];
  }

  /// 걷기 판정이 무한 평면이어도 탐색이 끝나도록 거는 상한.
  static const _maxExpanded = 20000;

  static const _dirs = [(1, 0), (-1, 0), (0, 1), (0, -1)];

  static List<(int, int)> _rebuild(
      Map<(int, int), (int, int)> cameFrom, (int, int) goal) {
    final path = <(int, int)>[goal];
    var t = goal;
    while (cameFrom.containsKey(t)) {
      t = cameFrom[t]!;
      path.add(t);
    }
    return path.reversed.toList();
  }
}
