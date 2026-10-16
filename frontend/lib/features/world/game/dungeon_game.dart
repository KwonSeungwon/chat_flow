import 'dart:math';
import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';
import 'package:flame/game.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show KeyEventResult, visibleForTesting;

import 'encounter_counter.dart';
import 'iso_grid.dart';
import 'student_avatar.dart';
import 'village_game.dart' show directionFromKeys, movementKeys;
import 'world_sprite.dart';

/// 건물 안 던전 방. 걸음마다 [EncounterCounter]를 돌려 인카운터가 나면
/// 그 자리에서 멈추고 [onEncounter]를 부른다. 이동은 [resume]까지 멈춘다.
///
/// 게임 하나는 GameWidget 하나의 수명 동안만 쓴다. 위젯이 dispose되면
/// [onDispose]가 그림 캐시를 모두 놓는다.
class DungeonGame extends FlameGame<TapWorld> with KeyboardEvents {
  DungeonGame(this.dungeonId, {this.onEncounter, Random? rng})
      : _counter = EncounterCounter(rng ?? Random()),
        super(world: TapWorld());

  final String dungeonId;
  void Function()? onEncounter;

  static const grid = IsoGrid(tileW: 64, tileH: 32);
  static const roomSize = 12;
  static const startTile = (6, 11);

  /// 방 안의 기둥(걸을 수 없음).
  static const pillars = {(3, 3), (8, 3), (3, 8), (8, 8)};

  /// 건물별 강조색(던전 깃발·융단). 마을 지붕 색과 같다.
  static const accents = {
    'k8s': Color(0xFFFF7A6B),
    'jvm': Color(0xFFF0A35B),
    'db': Color(0xFF6C8CFF),
    'net': Color(0xFF34C3B5),
    'build': Color(0xFFFFD34D),
    'ops': Color(0xFF9B7BFF),
    'ai': Color(0xFF5BD1E8),
    'shell': Color(0xFF5BD18A),
  };

  static bool isWalkable(int col, int row) =>
      col >= 0 &&
      row >= 0 &&
      col < roomSize &&
      row < roomSize &&
      !pillars.contains((col, row));

  final EncounterCounter _counter;
  late final StudentAvatar avatar;
  late final _DungeonFloor _floor;
  final _sky = SkyBackdrop(const Color(0xFF2A3358), const Color(0xFF4A5A8C));

  bool _paused = false;
  bool _boss = false;
  (int, int)? _heldDir;

  /// 지금 눌려 있는 키. 테스트에서만 바꾼다([VillageGame.pressedKeys]와 같다).
  @visibleForTesting
  Set<LogicalKeyboardKey> Function() pressedKeys =
      () => HardwareKeyboard.instance.logicalKeysPressed;

  bool get isPaused => _paused;
  bool get bossPhase => _boss;

  /// 배틀 중: 학생이 그 자리에 멈추고 입력을 받지 않는다.
  void pause() {
    _paused = true;
    _heldDir = null;
    if (isLoaded) {
      avatar
        ..stop()
        ..frozen = true;
    }
  }

  void resume() {
    _paused = false;
    if (isLoaded) avatar.frozen = false;
  }

  /// 보스 단계면 바닥과 배경을 어둡게 한다.
  void setBossPhase(bool boss) {
    if (_boss == boss) return;
    _boss = boss;
    if (boss) {
      _sky.setColors(const Color(0xFF141830), const Color(0xFF2A2F52));
    } else {
      _sky.setColors(const Color(0xFF2A3358), const Color(0xFF4A5A8C));
    }
    if (isLoaded) _floor.bake(boss: boss);
  }

  @override
  Color backgroundColor() => const Color(0xFF2A3358);

  @override
  Future<void> onLoad() async {
    world.onTap = _onTapWorld;
    final accent = accents[dungeonId] ?? const Color(0xFF6C8CFF);
    world.add(_floor = _DungeonFloor(accent)..bake(boss: _boss));
    for (final (c, r) in pillars) {
      world.add(_Pillar(c, r));
    }
    avatar = StudentAvatar(
      grid: grid,
      tile: startTile,
      onTileReached: _onTileReached,
    )..frozen = _paused;
    world.add(avatar);
    camera.viewfinder.anchor = Anchor.center;
    camera.follow(avatar, maxSpeed: 700);
  }

  @override
  void onGameResize(Vector2 size) {
    super.onGameResize(size);
    _sky.resize(size.toSize());
    camera.viewfinder.zoom = (size.x / 900).clamp(0.6, 1.1);
  }

  @override
  void render(Canvas canvas) {
    _sky.render(canvas);
    super.render(canvas);
  }

  @override
  void onDispose() {
    _heldDir = null;
    dispose();
    // 컴포넌트 제거가 대기열에 남아 onRemove가 아직 안 불렸을 수 있어 직접 놓는다.
    if (isLoaded) {
      _floor.release();
      avatar.releaseAssets();
    }
    super.onDispose();
  }

  @override
  void onDetach() {
    _heldDir = null;
    super.onDetach();
  }

  @override
  void update(double dt) {
    if (_heldDir != null && directionFromKeys(pressedKeys()) != _heldDir) {
      _heldDir = null;
    }
    final dir = _heldDir;
    if (!_paused && dir != null && !avatar.isMoving) {
      final (c, r) = avatar.tile;
      final next = (c + dir.$1, r + dir.$2);
      if (isWalkable(next.$1, next.$2)) avatar.moveAlong([next]);
    }
    super.update(dt);
  }

  @override
  KeyEventResult onKeyEvent(
      KeyEvent event, Set<LogicalKeyboardKey> keysPressed) {
    _heldDir = _paused ? null : directionFromKeys(keysPressed);
    return _heldDir != null || movementKeys.contains(event.logicalKey)
        ? KeyEventResult.handled
        : KeyEventResult.ignored;
  }

  void _onTapWorld(Vector2 worldPos) {
    if (_paused) return;
    final goal = grid.toTile(worldPos);
    final path = grid.findPath(avatar.tile, goal, isWalkable);
    if (path.isNotEmpty) avatar.moveAlong(path);
  }

  void _onTileReached(int col, int row) {
    if (_paused || !_counter.step()) return;
    pause();
    onEncounter?.call();
  }
}

/// 바닥·뒷벽·깃발을 Picture로 굽는다. 보스 단계 전환 때만 다시 굽는다.
class _DungeonFloor extends Component {
  _DungeonFloor(this.accent) : super(priority: -0x7ffffff);

  final Color accent;
  Picture? _picture;

  static const _wallH = 96.0;

  void bake({required bool boss}) {
    final dim = boss ? 0.55 : 1.0;
    Color d(Color c) => Color.from(
        alpha: c.a, red: c.r * dim, green: c.g * dim, blue: c.b * dim);

    const g = DungeonGame.grid;
    const n = DungeonGame.roomSize;
    final rugTop = Color.lerp(const Color(0xFF6B7CB3), accent, 0.45)!;
    final baker = IsoTileBaker(g, depth: 18);
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        final rug = c >= 5 && c <= 6;
        final top = rug
            ? d(rugTop)
            : d((c + r).isEven
                ? const Color(0xFF6B7CB3)
                : const Color(0xFF5D6EA3));
        baker.addTile(c, r, top);
        if (r == n - 1) baker.addLeftSide(c, r, d(const Color(0xFF4A5A8C)));
        if (c == n - 1) baker.addRightSide(c, r, d(const Color(0xFF3A4675)));
      }
    }

    final recorder = PictureRecorder();
    final canvas = Canvas(recorder);
    baker.paintShadow(canvas, n, n, const Color(0xFF0B1028));
    _paintWalls(canvas, d);
    baker.paintFaces(canvas, outline: const Color(0x14000000));
    if (boss) _paintBossGlow(canvas);
    _picture?.dispose();
    _picture = recorder.endRecording();
  }

  void _paintWalls(Canvas canvas, Color Function(Color) d) {
    const g = DungeonGame.grid;
    const n = DungeonGame.roomSize;
    final left = Paint()..color = d(const Color(0xFF46538A));
    final right = Paint()..color = d(const Color(0xFF3A4675));
    final cap = Paint()..color = d(const Color(0xFF7F8FC4));
    final banner = Paint()..color = d(accent);
    final torch = Paint()..color = const Color(0xFFFFD34D);
    final glow = Paint()..color = const Color(0x33FFD34D);

    // col 0 줄의 뒤쪽(왼쪽 위) 벽.
    final l = g.toScreen(0, n - 1) - Vector2(g.halfW, 0);
    final t = g.toScreen(0, 0) - Vector2(0, g.halfH);
    final r = g.toScreen(n - 1, 0) + Vector2(g.halfW, 0);
    canvas.drawPath(
        Path()
          ..addPolygon([
            Offset(l.x, l.y),
            Offset(t.x, t.y),
            Offset(t.x, t.y - _wallH),
            Offset(l.x, l.y - _wallH),
          ], true),
        left);
    canvas.drawPath(
        Path()
          ..addPolygon([
            Offset(t.x, t.y),
            Offset(r.x, r.y),
            Offset(r.x, r.y - _wallH),
            Offset(t.x, t.y - _wallH),
          ], true),
        right);
    // 벽 윗단.
    canvas.drawPath(
        Path()
          ..addPolygon([
            Offset(l.x, l.y - _wallH),
            Offset(t.x, t.y - _wallH),
            Offset(r.x, r.y - _wallH),
            Offset(r.x, r.y - _wallH - 8),
            Offset(t.x, t.y - _wallH - 8),
            Offset(l.x, l.y - _wallH - 8),
          ], true),
        cap);

    // 깃발과 횃불을 양쪽 벽에 번갈아 건다.
    for (var i = 1; i < n; i += 2) {
      final fl = Offset.lerp(Offset(l.x, l.y), Offset(t.x, t.y), i / n)!;
      final fr = Offset.lerp(Offset(t.x, t.y), Offset(r.x, r.y), i / n)!;
      if (i % 4 == 1) {
        _banner(canvas, fl, 0.5, banner);
        _banner(canvas, fr, -0.5, banner);
      } else {
        for (final p in [fl, fr]) {
          canvas.drawCircle(p.translate(0, -60), 12, glow);
          canvas.drawCircle(p.translate(0, -60), 4, torch);
        }
      }
    }
  }

  /// 벽 기울기([slope])를 따르는 세로 깃발.
  void _banner(Canvas canvas, Offset base, double slope, Paint paint) {
    const w = 14.0;
    final top = base.translate(0, -80);
    canvas.drawPath(
      Path()
        ..addPolygon([
          top.translate(-w / 2, w / 2 * slope),
          top.translate(w / 2, -w / 2 * slope),
          top.translate(w / 2, 44 - w / 2 * slope),
          top.translate(0, 36),
          top.translate(-w / 2, 44 + w / 2 * slope),
        ], true),
      paint,
    );
  }

  void _paintBossGlow(Canvas canvas) {
    final c = DungeonGame.grid.toScreen(5, 0) + Vector2(16, 8);
    final p = Paint();
    for (var i = 0; i < 4; i++) {
      p.color = const Color(0xFFFF5F6D).withValues(alpha: 0.07);
      canvas.drawOval(
          Rect.fromCenter(
              center: Offset(c.x, c.y),
              width: 120.0 + i * 50,
              height: 60.0 + i * 25),
          p);
    }
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

/// 방 안 돌기둥. 경로는 생성 때 한 번만 만든다.
class _Pillar extends PositionComponent
    with HasGameReference<FlameGame>, OffscreenCulling {
  _Pillar(int col, int row)
      : super(
          position: DungeonGame.grid.toScreen(col, row),
          size: Vector2(44, 110),
          anchor: const Anchor(0.5, 0.86),
          priority: isoDepth((col + row).toDouble()),
        );

  @override
  bool get cullsStatically => true;

  static final _left = Paint()..color = const Color(0xFF4F5D94);
  static final _right = Paint()..color = const Color(0xFF3A4675);
  static final _top = Paint()..color = const Color(0xFF8E9DD0);
  static final _shadow = Paint()..color = const Color(0x33000000);

  // 로컬 좌표: 바닥 중심 (22, 94.6), 반폭 18, 반높이 9, 높이 80.
  static final Path _l = Path()
    ..addPolygon(const [
      Offset(4, 94.6),
      Offset(22, 103.6),
      Offset(22, 23.6),
      Offset(4, 14.6)
    ], true);
  static final Path _r = Path()
    ..addPolygon(const [
      Offset(22, 103.6),
      Offset(40, 94.6),
      Offset(40, 14.6),
      Offset(22, 23.6)
    ], true);
  static final Path _t = Path()
    ..addPolygon(const [
      Offset(4, 14.6),
      Offset(22, 5.6),
      Offset(40, 14.6),
      Offset(22, 23.6)
    ], true);

  @override
  void render(Canvas canvas) {
    canvas.drawOval(
        Rect.fromCenter(center: const Offset(26, 98), width: 50, height: 20),
        _shadow);
    canvas.drawPath(_l, _left);
    canvas.drawPath(_r, _right);
    canvas.drawPath(_t, _top);
  }
}
