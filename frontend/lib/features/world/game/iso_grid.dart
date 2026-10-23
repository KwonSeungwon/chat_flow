import 'dart:collection';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flame/components.dart';

import 'top_down_grid.dart';
import 'world_sprite.dart';

/// 2:1 아이소메트릭 격자. 타일 (col,row)의 윗면 마름모 중심을 화면 좌표로 바꾸고,
/// 4방향 A* 경로를 찾는다.
class IsoGrid implements AvatarGrid {
  const IsoGrid({this.tileW = 64, this.tileH = 32});

  final double tileW;
  final double tileH;

  double get halfW => tileW / 2;
  double get halfH => tileH / 2;

  @override
  double get stepLength => math.sqrt(halfW * halfW + halfH * halfH);

  @override
  int depthAt(double y) => isoDepth(y / halfH);

  /// 타일 윗면 마름모의 중심.
  @override
  Vector2 toScreen(int col, int row) =>
      Vector2((col - row) * halfW, (col + row) * halfH);

  /// 화면 좌표가 들어 있는 타일. 마름모 안의 점은 그 타일로 돌아간다.
  (int, int) toTile(Vector2 screen) {
    final a = screen.x / halfW; // col - row
    final b = screen.y / halfH; // col + row
    return (((a + b) / 2).round(), ((b - a) / 2).round());
  }

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

/// 타일 면 색 세 가지(윗면, 왼쪽 옆면, 오른쪽 옆면).
class TileColors {
  const TileColors(this.top, this.left, this.right);
  final Color top;
  final Color left;
  final Color right;
}

/// 섬 하나를 마름모 폴리곤으로 그려 [Picture]로 굽는다. 색별로 Path를 합쳐
/// drawPath 호출 수를 색 수만큼으로 줄인다.
class IsoTileBaker {
  IsoTileBaker(this.grid, {this.depth = 14});

  final IsoGrid grid;
  final double depth;

  final _tops = <Color, Path>{};
  final _sides = <Color, Path>{};

  void addTile(int col, int row, Color top) {
    final c = grid.toScreen(col, row);
    (_tops[top] ??= Path()).addPolygon([
      Offset(c.x, c.y - grid.halfH),
      Offset(c.x + grid.halfW, c.y),
      Offset(c.x, c.y + grid.halfH),
      Offset(c.x - grid.halfW, c.y),
    ], true);
  }

  /// 섬 가장자리 타일의 왼쪽(row 방향) 옆면.
  void addLeftSide(int col, int row, Color color) {
    final c = grid.toScreen(col, row);
    final l = Offset(c.x - grid.halfW, c.y);
    final b = Offset(c.x, c.y + grid.halfH);
    (_sides[color] ??= Path())
        .addPolygon([l, b, b.translate(0, depth), l.translate(0, depth)], true);
  }

  /// 섬 가장자리 타일의 오른쪽(col 방향) 옆면.
  void addRightSide(int col, int row, Color color) {
    final c = grid.toScreen(col, row);
    final b = Offset(c.x, c.y + grid.halfH);
    final r = Offset(c.x + grid.halfW, c.y);
    (_sides[color] ??= Path())
        .addPolygon([b, r, r.translate(0, depth), b.translate(0, depth)], true);
  }

  /// cols×rows 섬 전체 아래에 번지는 그림자. 블러 대신 반투명 마름모를 겹친다.
  void paintShadow(Canvas canvas, int cols, int rows, Color color) {
    final top = grid.toScreen(0, 0) - Vector2(0, grid.halfH);
    final right = grid.toScreen(cols - 1, 0) + Vector2(grid.halfW, 0);
    final bottom = grid.toScreen(cols - 1, rows - 1) + Vector2(0, grid.halfH);
    final left = grid.toScreen(0, rows - 1) - Vector2(grid.halfW, 0);
    final paint = Paint()..color = color.withValues(alpha: 0.06);
    for (var i = 0; i < 4; i++) {
      final grow = 6.0 + i * 8;
      final dy = depth + 10 + i * 3;
      canvas.drawPath(
        Path()
          ..addPolygon([
            Offset(top.x, top.y + dy - grow / 2),
            Offset(right.x + grow, right.y + dy),
            Offset(bottom.x, bottom.y + dy + grow / 2),
            Offset(left.x - grow, left.y + dy),
          ], true),
        paint,
      );
    }
  }

  /// 지금까지 넣은 면을 그린다. [outline]이 있으면 격자선을 아주 옅게 긋는다.
  void paintFaces(Canvas canvas, {Color? outline}) {
    final paint = Paint()..isAntiAlias = true;
    for (final e in _sides.entries) {
      canvas.drawPath(e.value, paint..color = e.key);
    }
    for (final e in _tops.entries) {
      canvas.drawPath(e.value, paint..color = e.key);
    }
    if (outline != null) {
      final stroke = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..color = outline;
      for (final p in _tops.values) {
        canvas.drawPath(p, stroke);
      }
    }
  }
}
