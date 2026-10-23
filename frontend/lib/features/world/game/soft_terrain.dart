import 'dart:math' as math;
import 'dart:ui';

import 'package:flame/components.dart';

import 'top_down_grid.dart';
import 'village_map.dart';

/// 마을 바닥(잔디·흙길·광장·연못·꽃)을 한 번 [Picture]로 구워 매 프레임 재생만 한다.
/// 그림은 시안 v3(32px 칸)를 [TopDownGrid.tile] 크기로 늘려 그린다.
class VillageTerrain extends Component {
  VillageTerrain(this.grid) : super(priority: -0x7ffffff);

  final TopDownGrid grid;
  Picture? _picture;

  /// 광장(벽돌) 칸 범위. 분수가 가운데 있다.
  static bool isPlaza(int c, int r) => c >= 8 && c <= 13 && r >= 9 && r <= 13;

  @override
  Future<void> onLoad() async {
    final recorder = PictureRecorder();
    _SoftTerrainPainter(Canvas(recorder), grid.tile).paint();
    _picture = recorder.endRecording();
  }

  @override
  void render(Canvas canvas) {
    final p = _picture;
    if (p != null) canvas.drawPicture(p);
  }

  void release() {
    _picture?.dispose();
    _picture = null;
  }

  @override
  void onRemove() {
    release();
    super.onRemove();
  }
}

/// 0..1 결정적 해시. 같은 입력이면 웹·네이티브 모두 같은 값이다.
double _hash(int i) => ((i * 2654435761) % 4294967296 % 1000) / 1000;

class _SoftTerrainPainter {
  _SoftTerrainPainter(this.canvas, this.s) : u = s / 32;

  final Canvas canvas;

  /// 한 칸의 월드 크기.
  final double s;

  /// 시안 1단위(32px 칸 기준)의 월드 크기.
  final double u;

  static const n = VillageMap.size;

  static bool _road(int c, int r) => switch (VillageMap.tileAt(c, r)) {
        'P' || 'D' || 'S' || 'F' => true,
        _ => false,
      };

  static bool _water(int c, int r) => VillageMap.tileAt(c, r) == 'W';

  void paint() {
    _grass();
    _blob(_road, rim: const Color(0xFFD9C08F), fill: const Color(0xFFF3E0B1));
    _pathSpeckles();
    _plaza();
    _blob(_water, rim: const Color(0xFF7CC9A0), fill: const Color(0xFF8ADBEE));
    _waterRipples();
    _flowers();
  }

  void _grass() {
    // 맵 둘레 두 칸까지 잔디를 깐다(카메라가 경계에 닿아도 빈 곳이 없게).
    final area = Rect.fromLTWH(-2 * s, -2 * s, (n + 4) * s, (n + 4) * s);
    canvas.drawRect(
      area,
      Paint()
        ..shader = Gradient.linear(Offset(0, area.top), Offset(0, area.bottom),
            const [Color(0xFFA9E28F), Color(0xFF93D77D)]),
    );
    final light = Paint()..color = const Color(0x2EFFFFFF);
    final dark = Paint()..color = const Color(0x1F469646);
    const count = (n + 4) * (n + 4) * 5 ~/ 2;
    for (var i = 0; i < count; i++) {
      final x = area.left + _hash(i) * area.width;
      final y = area.top + _hash(i * 7 + 3) * area.height;
      final rx = (3 + _hash(i * 5) * 4) * u;
      canvas.drawOval(
        Rect.fromCenter(center: Offset(x, y), width: rx * 2, height: 3.2 * u),
        _hash(i * 3) > 0.5 ? light : dark,
      );
    }
  }

  /// [inside] 칸을 이어 붙인 둥근 덩어리 하나(길·연못). 바깥 모서리만 둥글리고
  /// 안쪽으로 꺾인 곳은 살짝 메워서 타일 이음매가 보이지 않게 한다.
  void _blob(bool Function(int c, int r) inside,
      {required Color rim, required Color fill}) {
    final radius = 9 * u;
    final shape = Path();
    for (var r = -1; r <= n; r++) {
      for (var c = -1; c <= n; c++) {
        if (inside(c, r)) {
          Radius corner(int dc, int dr) =>
              !inside(c + dc, r) && !inside(c, r + dr)
                  ? Radius.circular(radius)
                  : Radius.zero;
          shape.addRRect(RRect.fromRectAndCorners(
            Rect.fromLTWH(c * s, r * s, s, s),
            topLeft: corner(-1, -1),
            topRight: corner(1, -1),
            bottomLeft: corner(-1, 1),
            bottomRight: corner(1, 1),
          ));
        } else {
          for (final (dc, dr) in const [(-1, -1), (1, -1), (-1, 1), (1, 1)]) {
            if (inside(c + dc, r) && inside(c, r + dr)) {
              _addFillet(shape, c, r, dc, dr, radius);
            }
          }
        }
      }
    }
    // 아래로 살짝 내린 테두리 + 둘레 선. 안쪽 선은 위에 칠하는 면이 덮는다.
    final rimPaint = Paint()..color = rim;
    canvas.drawPath(shape.shift(Offset(0, 2.5 * u)), rimPaint);
    canvas.drawPath(
        shape,
        Paint()
          ..color = rim
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3 * u);
    canvas.drawPath(shape, Paint()..color = fill);
  }

  /// 길이 아닌 칸 (c,r)의 (dc,dr) 쪽 모서리에 오목한 메움 조각을 넣는다.
  void _addFillet(Path path, int c, int r, int dc, int dr, double radius) {
    final px = (c + (dc > 0 ? 1 : 0)) * s;
    final py = (r + (dr > 0 ? 1 : 0)) * s;
    final p = Offset(px, py);
    final a = Offset(px - dc * radius, py); // 모서리에서 칸 안쪽 가로
    final b = Offset(px, py - dr * radius); // 모서리에서 칸 안쪽 세로
    const k = 0.5523; // 4분원을 3차 곡선으로 근사하는 계수
    path
      ..moveTo(p.dx, p.dy)
      ..lineTo(a.dx, a.dy)
      ..cubicTo(a.dx + (p.dx - a.dx) * k, a.dy, b.dx, b.dy + (p.dy - b.dy) * k,
          b.dx, b.dy)
      ..close();
  }

  void _pathSpeckles() {
    final dot = Paint()..color = const Color(0x40BEA06E);
    var i = 0;
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        if (!_road(c, r) || VillageTerrain.isPlaza(c, r)) continue;
        for (var k = 0; k < 3; k++, i++) {
          canvas.drawCircle(
              Offset(c * s + _hash(i) * s, r * s + _hash(i * 9) * s),
              0.9 * u,
              dot);
        }
      }
    }
  }

  void _plaza() {
    const c0 = 8, r0 = 9, cols = 6, rows = 5;
    final inset = 4 * u;
    final base = Rect.fromLTWH(c0 * s + inset, r0 * s + inset,
        cols * s - 2 * inset, rows * s - 2 * inset);
    canvas.drawRRect(RRect.fromRectAndRadius(base, Radius.circular(8 * u)),
        Paint()..color = const Color(0xFFDCCDAE));
    final a = Paint()..color = const Color(0xFFEFE7D6);
    final b = Paint()..color = const Color(0xFFE8DFCC);
    final hi = Paint()..color = const Color(0x59FFFFFF);
    // 반 칸 벽돌을 한 줄씩 엇갈려 깐다.
    final brick = s / 2;
    final gap = 1.5 * u;
    for (var y = 0; y * brick < base.height - 1; y++) {
      final offset = y.isOdd ? brick / 2 : 0.0;
      for (var x = -1; x * brick < base.width; x++) {
        final left = base.left + x * brick + offset;
        final rect = Rect.fromLTRB(
          math.max(left + gap / 2, base.left + gap),
          base.top + y * brick + gap / 2,
          math.min(left + brick - gap / 2, base.right - gap),
          math.min(base.top + (y + 1) * brick - gap / 2, base.bottom - gap),
        );
        if (rect.width < 2 || rect.height < 2) continue;
        final rr = RRect.fromRectAndRadius(rect, Radius.circular(3 * u));
        canvas.drawRRect(rr, (x + y).isEven ? a : b);
        canvas.drawRRect(
            RRect.fromRectAndRadius(
                Rect.fromLTWH(rect.left + 2 * u, rect.top + 1.2 * u,
                    rect.width * 0.45, 1.6 * u),
                Radius.circular(u)),
            hi);
      }
    }
  }

  void _waterRipples() {
    final line = Paint()
      ..color = const Color(0x8CFFFFFF)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2 * u
      ..strokeCap = StrokeCap.round;
    var i = 0;
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        if (!_water(c, r)) continue;
        for (var k = 0; k < 2; k++, i++) {
          final x = c * s + (6 + _hash(i * 13) * 12) * u;
          final y = r * s + (8 + k * 12 + _hash(i * 17) * 4) * u;
          canvas.drawPath(
            Path()
              ..moveTo(x, y)
              ..quadraticBezierTo(x + 4 * u, y + 2.5 * u, x + 8 * u, y)
              ..quadraticBezierTo(x + 12 * u, y - 2.5 * u, x + 16 * u, y),
            line,
          );
        }
      }
    }
  }

  void _flowers() {
    const colors = [
      Color(0xFFFF9FB2),
      Color(0xFFFFFFFF),
      Color(0xFFFFC98A),
      Color(0xFFC9B6FF),
    ];
    final petal = Paint();
    final heart = Paint()..color = const Color(0xFFFFE58A);
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        final i = r * n + c;
        if (VillageMap.tileAt(c, r) != 'G' || _hash(i) <= 0.74) continue;
        petal.color = colors[(_hash(i * 3) * 4).floor()];
        for (var k = 0; k < 3; k++) {
          final x = c * s + (6 + _hash(i + k) * 20) * u;
          final y = r * s + (6 + _hash(i * 5 + k) * 20) * u;
          for (var a = 0; a < 5; a++) {
            final t = a / 5 * math.pi * 2;
            canvas.drawCircle(
                Offset(x + math.cos(t) * 2.2 * u, y + math.sin(t) * 2.2 * u),
                1.8 * u,
                petal);
          }
          canvas.drawCircle(Offset(x, y), 1.3 * u, heart);
        }
      }
    }
  }
}
