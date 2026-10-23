import 'dart:math' as math;
import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/game.dart';
import 'package:flame_svg/flame_svg.dart';

import '../state/game_models.dart';
import 'top_down_grid.dart';
import 'world_sprite.dart';

/// 바라보는 쪽. 왼쪽은 [side]를 좌우 반전해 그린다.
enum _Facing { down, up, side }

/// 대학생 주인공([look]: 남·여). 타일 경로를 따라 걷고(가는 방향의 그림 +
/// 살짝 흔들림), 정답에 기뻐하고, 졸업하면 학사모를 쓴다.
class StudentAvatar extends PositionComponent
    with HasGameReference<FlameGame>, OffscreenCulling {
  StudentAvatar({
    required this.grid,
    required (int, int) tile,
    this.look = StudentLook.boy,
    this.onTileReached,
    this.tilesPerSecond = 4.5,
  })  : _tile = tile,
        super(
          position: grid.toScreen(tile.$1, tile.$2),
          size: Vector2(40, 56),
          // 신발 바닥이 타일 중심에 오도록.
          anchor: const Anchor(0.5, 0.97),
        );

  final AvatarGrid grid;
  final StudentLook look;
  final double tilesPerSecond;

  /// 한 칸에 도착할 때마다 불린다. 여기서 [stop]을 부르면 남은 경로를 버린다.
  void Function(int col, int row)? onTileReached;

  /// true면 걷던 자리에서 멈춘다(배틀 중).
  bool frozen = false;

  (int, int) _tile;
  (int, int)? _target;
  final _queue = <(int, int)>[];
  final _targetPos = Vector2.zero();
  final _tmp = Vector2.zero();

  _Facing _facing = _Facing.down;
  bool _facingLeft = false;
  double _walkClock = 0;
  double _celebrate = -1; // 0..1 진행, 음수면 쉬는 중.

  final _bodies = <_Facing, Svg>{};
  Svg? _cap;

  static const _celebrateSeconds = 0.9;
  final _shadowPaint = Paint()..color = const Color(0x38283C50);
  final _starPaint = Paint()..color = const Color(0xFFFFD34D);

  /// 지금 서 있거나 향하고 있는 칸.
  (int, int) get tile => _target ?? _tile;

  bool get isMoving => _target != null || _queue.isNotEmpty;

  /// true면 학사모 레이어를 씌운다.
  bool graduated = false;

  @override
  Future<void> onLoad() async {
    final loaded = await Future.wait([
      for (final f in _Facing.values)
        game.loadSvg('world/student_${look.name}_${f.name}.svg'),
      game.loadSvg('world/student_cap.svg'),
    ]);
    for (final f in _Facing.values) {
      _bodies[f] = loaded[f.index];
    }
    _cap = loaded.last;
    // 읽는 사이에 게임이 dispose됐으면 바로 놓는다.
    if (_released) releaseAssets();
    _updatePriority();
  }

  @override
  void onRemove() {
    releaseAssets();
    super.onRemove();
  }

  /// SVG 래스터 캐시와 Picture를 놓는다. 여러 번 불러도 된다. 게임이 dispose될
  /// 때 컴포넌트 제거가 끝나지 않았어도 놓을 수 있게 따로 열어 둔다.
  void releaseAssets() {
    _released = true;
    for (final svg in [..._bodies.values, _cap]) {
      svg?.dispose();
      svg?.pictureInfo.picture.dispose();
    }
    _bodies.clear();
    _cap = null;
  }

  bool _released = false;

  /// [releaseAssets]가 불렸고 들고 있는 SVG가 없다.
  bool get assetsReleased => _released && _bodies.isEmpty && _cap == null;

  /// [path]를 따라 걷는다. 앞쪽에 지금 칸과 같은 타일이 있으면 건너뛴다.
  /// 걷는 중이면 지금 칸까지 마저 간 뒤 새 경로를 따른다.
  void moveAlong(List<(int, int)> path) {
    _queue
      ..clear()
      ..addAll(path.skipWhile((t) => t == tile));
  }

  /// 남은 경로를 버린다. 걷던 한 칸은 마저 간다.
  void stop() => _queue.clear();

  /// 순간 이동(씬 진입 등).
  void placeAt((int, int) t) {
    _queue.clear();
    _target = null;
    _tile = t;
    position.setFrom(grid.toScreen(t.$1, t.$2));
    _updatePriority();
  }

  void celebrate() => _celebrate = 0;

  @override
  void update(double dt) {
    super.update(dt);
    if (_celebrate >= 0) {
      _celebrate += dt / _celebrateSeconds;
      if (_celebrate >= 1) _celebrate = -1;
    }
    if (frozen) return;

    if (_target == null && _queue.isNotEmpty) {
      _target = _queue.removeAt(0);
      _targetPos.setFrom(grid.toScreen(_target!.$1, _target!.$2));
      final dx = _targetPos.x - position.x;
      final dy = _targetPos.y - position.y;
      if (dx.abs() > dy.abs()) {
        _facing = _Facing.side;
        _facingLeft = dx < 0;
      } else if (dy != 0) {
        _facing = dy > 0 ? _Facing.down : _Facing.up;
      }
    }
    final target = _target;
    if (target == null) {
      _walkClock = 0;
      return;
    }

    _walkClock += dt;
    final maxMove = grid.stepLength * tilesPerSecond * dt;
    final toGo = _tmp
      ..setFrom(_targetPos)
      ..sub(position);
    if (toGo.length <= maxMove) {
      position.setFrom(_targetPos);
      _tile = target;
      _target = null;
      _updatePriority();
      onTileReached?.call(target.$1, target.$2);
    } else {
      position.add(toGo..scaleTo(maxMove));
      _updatePriority();
    }
  }

  void _updatePriority() {
    final p = grid.depthAt(position.y);
    if (priority != p) priority = p;
  }

  @override
  void render(Canvas canvas) {
    final body = _bodies[_facing];
    if (body == null) return;
    final w = size.x;
    final h = size.y;
    final feet = Offset(w / 2, h * 0.97);

    var lift = 0.0;
    var sway = 0.0;
    if (_celebrate >= 0) {
      // 두 번 깡충.
      lift = (math.sin(_celebrate * math.pi * 2)).abs() * 14;
    } else if (_target != null) {
      lift = (math.sin(_walkClock * 16)).abs() * 2.5;
      sway = math.sin(_walkClock * 8) * 0.06;
    }

    // 그림자는 발밑에 그대로 두고 뛰어오를수록 작아진다.
    final shadowScale = 1 - lift / 40;
    canvas.drawOval(
      Rect.fromCenter(
          center: feet, width: 26 * shadowScale, height: 8 * shadowScale),
      _shadowPaint,
    );

    canvas.save();
    canvas.translate(0, -lift);
    if (sway != 0) {
      canvas.translate(feet.dx, feet.dy);
      canvas.rotate(sway);
      canvas.translate(-feet.dx, -feet.dy);
    }
    if (_facing == _Facing.side && _facingLeft) {
      canvas.translate(w, 0);
      canvas.scale(-1, 1);
    }
    body.render(canvas, size);
    if (graduated) _cap?.render(canvas, size);
    canvas.restore();

    if (_celebrate >= 0) _renderStars(canvas, w);
  }

  void _renderStars(Canvas canvas, double w) {
    final t = _celebrate;
    for (var i = 0; i < 3; i++) {
      final angle = -math.pi / 2 + (i - 1) * 0.9;
      final r = 16 + t * 20;
      final c = Offset(w / 2 + math.cos(angle) * r, 10 + math.sin(angle) * r);
      _starPaint.color = Color.fromRGBO(255, 211, 77, 1 - t);
      canvas.drawCircle(c, 3.5 * (1 - t) + 1, _starPaint);
    }
  }
}
