import 'dart:math';
import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';
import 'package:flame/game.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show KeyEventResult, visibleForTesting;

import '../state/game_models.dart';
import 'encounter_counter.dart';
import 'student_avatar.dart';
import 'top_down_grid.dart';
import 'village_game.dart' show directionFromKeys, movementKeys;
import 'world_sprite.dart';

/// 건물 안 던전 방(위에서 본 12×12칸). 걸음마다 [EncounterCounter]를 돌려
/// 인카운터가 나면 그 자리에서 멈추고 [onEncounter]를 부른다. 이동은 [resume]까지 멈춘다.
///
/// 게임 하나는 GameWidget 하나의 수명 동안만 쓴다. 위젯이 dispose되면
/// [onDispose]가 그림 캐시를 모두 놓는다.
class DungeonGame extends FlameGame<TapWorld> with KeyboardEvents {
  DungeonGame(
    this.dungeonId, {
    this.onEncounter,
    this.look = StudentLook.boy,
    Random? rng,
  })  : _counter = EncounterCounter(rng ?? Random()),
        super(world: TapWorld());

  final String dungeonId;
  final StudentLook look;
  void Function()? onEncounter;

  static const grid = TopDownGrid();
  static const roomSize = 12;
  static const startTile = (6, 11);

  /// 방 안의 기둥(걸을 수 없음).
  static const pillars = {(3, 3), (8, 3), (3, 8), (8, 8)};

  /// 건물별 강조색(바닥·깔개·깃발·기둥 띠). 마을 지붕 색과 같다.
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

  /// 뒷벽 높이(바닥 위쪽, 월드 px).
  static const wallHeight = 96.0;

  /// 방 그림 전체(뒷벽·옆벽·문 앞 깔개 포함)의 월드 영역. 카메라가 이 밖을 덜 보이게 멈춘다.
  static final roomExtent = Rect.fromLTRB(
      -_RoomPainter.sideW - 8,
      -wallHeight - _RoomPainter.capH - 8,
      roomSize * grid.tile + _RoomPainter.sideW + 8,
      roomSize * grid.tile + 40);

  static bool isWalkable(int col, int row) =>
      col >= 0 &&
      row >= 0 &&
      col < roomSize &&
      row < roomSize &&
      !pillars.contains((col, row));

  final EncounterCounter _counter;
  late final StudentAvatar avatar;
  late final _DungeonRoom _room;
  final _pillars = <(int, int), _Pillar>{};

  bool _paused = false;
  bool _boss = false;
  (int, int)? _heldDir;

  /// 지금 눌려 있는 키. 테스트에서만 바꾼다([VillageGame.pressedKeys]와 같다).
  @visibleForTesting
  Set<LogicalKeyboardKey> Function() pressedKeys =
      () => HardwareKeyboard.instance.logicalKeysPressed;

  bool get isPaused => _paused;
  bool get bossPhase => _boss;

  /// 기둥 하나의 그리기 순서. 주인공 [StudentAvatar.priority]와 비교한다.
  @visibleForTesting
  int pillarDepth((int, int) tile) => _pillars[tile]!.priority;

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

  /// 보스 단계면 방을 어둡게 하고 안쪽 아치에 붉은 빛을 켠다.
  void setBossPhase(bool boss) {
    if (_boss == boss) return;
    _boss = boss;
    if (!isLoaded) return;
    _room.bake(boss: boss);
    for (final p in _pillars.values) {
      p.setBoss(boss);
    }
  }

  Color get _accent => accents[dungeonId] ?? const Color(0xFF6C8CFF);

  @override
  Color backgroundColor() => _RoomPainter.outsideOf(_accent);

  @override
  Future<void> onLoad() async {
    world.onTap = _onTapWorld;
    world.add(_room = _DungeonRoom(_accent)..bake(boss: _boss));
    for (final (c, r) in pillars) {
      final p = _Pillar(c, r, _accent)..setBoss(_boss);
      _pillars[(c, r)] = p;
      world.add(p);
    }
    avatar = StudentAvatar(
      grid: grid,
      tile: startTile,
      look: look,
      onTileReached: _onTileReached,
    )..frozen = _paused;
    world.add(avatar);
    camera.viewfinder.anchor = Anchor.center;
    _followAvatar();
  }

  final _cameraTarget = Vector2.zero();

  /// 카메라를 주인공에 맞추되 [roomExtent] 밖이 보이지 않게 가장자리에서 멈춘다.
  /// 보이는 폭이 방보다 넓으면 그 축은 방 가운데에 둔다.
  void _followAvatar() {
    if (!isLoaded) return;
    final zoom = camera.viewfinder.zoom;
    final view = camera.viewport.virtualSize;
    final e = roomExtent;
    double clampAxis(double v, double half, double lo, double hi) =>
        half * 2 >= hi - lo ? (lo + hi) / 2 : v.clamp(lo + half, hi - half);
    camera.viewfinder.position = _cameraTarget
      ..setValues(
        clampAxis(avatar.position.x, view.x / (2 * zoom), e.left, e.right),
        clampAxis(avatar.position.y, view.y / (2 * zoom), e.top, e.bottom),
      );
  }

  @override
  void onGameResize(Vector2 size) {
    super.onGameResize(size);
    // 마을과 같은 확대: 짧은 변에 7칸, 한 칸은 화면에서 48~76px.
    final tile = (min(size.x, size.y) / 7).clamp(48.0, 76.0);
    camera.viewfinder.zoom = tile / grid.tile;
    _followAvatar();
  }

  @override
  void onDispose() {
    _heldDir = null;
    dispose();
    // 컴포넌트 제거가 대기열에 남아 onRemove가 아직 안 불렸을 수 있어 직접 놓는다.
    if (isLoaded) {
      _room.release();
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
    _followAvatar();
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

/// 보스 단계면 [c]를 어둡게 한다.
Color _dim(Color c, bool boss) {
  if (!boss) return c;
  const k = 0.58;
  return Color.from(alpha: c.a, red: c.r * k, green: c.g * k, blue: c.b * k);
}

/// 바닥·벽·깔개·창·깃발을 Picture로 굽는다. 보스 단계 전환 때만 다시 굽는다.
class _DungeonRoom extends Component {
  _DungeonRoom(this.accent) : super(priority: -0x7ffffff);

  final Color accent;
  Picture? _picture;

  void bake({required bool boss}) {
    final recorder = PictureRecorder();
    _RoomPainter(Canvas(recorder), accent, boss).paint();
    _picture?.dispose();
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

class _RoomPainter {
  _RoomPainter(this.canvas, this.accent, this.boss);

  final Canvas canvas;
  final Color accent;
  final bool boss;

  static const s = 48.0;
  static const n = DungeonGame.roomSize;
  static const w = n * s;
  static const wallH = DungeonGame.wallHeight;

  /// 옆벽 두께, 뒷벽 윗단 높이, 아래 문턱 두께.
  static const sideW = 16.0;
  static const capH = 14.0;
  static const rimH = 16.0;

  /// 방 바깥(어두운 복도) 색.
  static Color outsideOf(Color accent) =>
      Color.lerp(const Color(0xFF39354F), accent, 0.10)!;

  Color _mix(int base, double t) =>
      _dim(Color.lerp(Color(base), accent, t)!, boss);
  Color _tint(Color c, double toWhite) =>
      _dim(Color.lerp(c, const Color(0xFFFFFFFF), toWhite)!, boss);

  Paint _fill(Color c) => Paint()
    ..color = c
    ..isAntiAlias = true;

  void paint() {
    // 카메라가 방 끝에 닿아도 빈 곳이 없게 바깥을 넓게 칠한다.
    canvas.drawRect(
        const Rect.fromLTRB(-20 * s, -20 * s, w + 20 * s, w + 20 * s),
        _fill(_dim(outsideOf(accent), boss)));
    _shell();
    _floor();
    _rug();
    _backWall();
    _innerShadows();
    _doormat();
    if (boss) _bossGlow();
  }

  /// 방 전체 그림자와 옆·아래 벽(둥근 모서리 껍데기).
  void _shell() {
    final shell = RRect.fromRectAndRadius(
        const Rect.fromLTRB(-sideW, -wallH - capH, w + sideW, w + rimH),
        const Radius.circular(22));
    canvas.drawRRect(
        shell.shift(const Offset(0, 10)),
        Paint()
          ..color = const Color(0x40000000)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 14));
    canvas.drawRRect(
        shell,
        Paint()
          ..shader = Gradient.linear(
              const Offset(0, -wallH), const Offset(0, w + rimH), [
            _mix(0xFFCFC6DE, 0.18),
            _mix(0xFFB4A9C8, 0.22),
          ]));
    // 껍데기 윗면의 밝은 둘레선.
    canvas.drawRRect(
        shell.deflate(1.5),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.2
          ..color = _tint(_mix(0xFFE9E3F2, 0.1), 0.3).withValues(alpha: 0.7));
  }

  /// 둥근 모서리 바닥 타일. 줄눈 색 위에 두 가지 색을 체크로 깐다.
  void _floor() {
    canvas.drawRect(
        const Rect.fromLTWH(0, 0, w, w), _fill(_mix(0xFFE9DFD3, 0.20)));
    final a = Path();
    final b = Path();
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        final rect = Rect.fromLTWH(c * s + 1.5, r * s + 1.5, s - 3, s - 3);
        ((c + r).isEven ? a : b)
            .addRRect(RRect.fromRectAndRadius(rect, const Radius.circular(6)));
      }
    }
    canvas.drawPath(a, _fill(_mix(0xFFF7F2EA, 0.13)));
    canvas.drawPath(b, _fill(_mix(0xFFF2EBE1, 0.17)));
    // 타일 위쪽의 옅은 빛.
    final shine = Path();
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        shine.addRRect(RRect.fromRectAndRadius(
            Rect.fromLTWH(c * s + 7, r * s + 6, s - 14, 5),
            const Radius.circular(3)));
      }
    }
    canvas.drawPath(shine, _fill(Color(boss ? 0x0AFFFFFF : 0x1FFFFFFF)));
  }

  /// 가운데 두 칸(5~6열)을 따라 문에서 안쪽 아치까지 깔린 깔개.
  void _rug() {
    const rect = Rect.fromLTRB(5 * s + 5, 6, 7 * s - 5, w - 2);
    final rr = RRect.fromRectAndRadius(rect, const Radius.circular(14));
    canvas.drawRRect(
        rr.shift(const Offset(0, 3)), _fill(const Color(0x24283C50)));
    canvas.drawRRect(
        rr,
        Paint()
          ..shader = Gradient.linear(rect.topLeft, rect.topRight, [
            _tint(accent, 0.18),
            _tint(accent, 0.32),
            _tint(accent, 0.18),
          ], [
            0,
            0.5,
            1
          ]));
    // 안쪽 테두리 띠.
    canvas.drawRRect(
        rr.deflate(7),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3
          ..color = _tint(accent, 0.7).withValues(alpha: 0.85));
    // 두 칸마다 마름모 무늬.
    final motif = Path();
    final cx = rect.center.dx;
    for (var r = 1; r < n; r += 2) {
      final cy = r * s + s / 2;
      motif.addPath(
          Path()
            ..moveTo(cx, cy - 11)
            ..quadraticBezierTo(cx + 2, cy - 2, cx + 11, cy)
            ..quadraticBezierTo(cx + 2, cy + 2, cx, cy + 11)
            ..quadraticBezierTo(cx - 2, cy + 2, cx - 11, cy)
            ..quadraticBezierTo(cx - 2, cy - 2, cx, cy - 11)
            ..close(),
          Offset.zero);
    }
    canvas.drawPath(motif, _fill(_tint(accent, 0.62).withValues(alpha: 0.9)));
  }

  /// 위쪽 벽: 벽면 그러데이션, 아랫단 징두리, 윗단, 창 둘·깃발 둘·등 둘·가운데 아치.
  void _backWall() {
    const face = Rect.fromLTWH(0, -wallH, w, wallH);
    canvas.drawRect(
        face,
        Paint()
          ..shader = Gradient.linear(face.topCenter, face.bottomCenter, [
            _mix(0xFFF6F1FA, 0.10),
            _mix(0xFFE6DEEE, 0.16),
          ]));
    // 징두리(아랫단 판자).
    const wain = Rect.fromLTWH(0, -26, w, 26);
    canvas.drawRect(wain, _fill(_mix(0xFFD6CBE0, 0.24)));
    canvas.drawRect(const Rect.fromLTWH(0, -26, w, 3),
        _fill(_tint(_mix(0xFFD6CBE0, 0.24), 0.45)));
    final seams = Path();
    for (var x = s; x < w; x += s) {
      seams.addRRect(RRect.fromRectAndRadius(
          Rect.fromLTWH(x - 1, -20, 2, 16), const Radius.circular(1)));
    }
    canvas.drawPath(seams, _fill(_mix(0xFFC4B8D0, 0.26)));
    // 윗단.
    canvas.drawRRect(
        RRect.fromRectAndCorners(
            const Rect.fromLTWH(-sideW, -wallH - capH, w + 2 * sideW, capH + 2),
            topLeft: const Radius.circular(22),
            topRight: const Radius.circular(22)),
        _fill(_mix(0xFFFBF8FD, 0.08)));
    canvas.drawRect(
        const Rect.fromLTWH(0, -wallH, w, 4), _fill(const Color(0x1A283C50)));

    _window(1.5 * s);
    _window(10.5 * s);
    _banner(3.6 * s);
    _banner(8.4 * s);
    _sconce(0.35 * s);
    _sconce(11.65 * s);
    _arch(6 * s);
  }

  void _window(double cx) {
    final frame =
        Rect.fromCenter(center: Offset(cx, -58), width: 58, height: 46);
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            frame.shift(const Offset(0, 3)), const Radius.circular(12)),
        _fill(const Color(0x1F283C50)));
    canvas.drawRRect(RRect.fromRectAndRadius(frame, const Radius.circular(12)),
        _fill(_dim(const Color(0xFFFFFBF5), boss)));
    final glass = frame.deflate(5);
    canvas.drawRRect(
        RRect.fromRectAndRadius(glass, const Radius.circular(8)),
        Paint()
          ..shader = Gradient.linear(glass.topCenter, glass.bottomCenter, [
            _dim(
                boss ? const Color(0xFF6B5A9A) : const Color(0xFFCDEBFF), boss),
            _dim(
                boss ? const Color(0xFF3D3466) : const Color(0xFF94CDEE), boss),
          ]));
    // 창살과 빛.
    final bars = _fill(_dim(const Color(0xFFFFFBF5), boss));
    canvas.drawRect(Rect.fromLTWH(cx - 1.5, glass.top, 3, glass.height), bars);
    canvas.drawRect(
        Rect.fromLTWH(glass.left, glass.center.dy - 1.5, glass.width, 3), bars);
    canvas.drawPath(
        Path()
          ..moveTo(glass.left + 5, glass.top + 14)
          ..lineTo(glass.left + 14, glass.top + 4)
          ..lineTo(glass.left + 19, glass.top + 4)
          ..lineTo(glass.left + 5, glass.top + 19)
          ..close(),
        _fill(const Color(0x66FFFFFF)));
    // 창턱.
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            Rect.fromCenter(
                center: Offset(cx, frame.bottom + 2), width: 66, height: 7),
            const Radius.circular(4)),
        _fill(_mix(0xFFEDE6F3, 0.12)));
  }

  void _banner(double cx) {
    const top = -84.0;
    const bw = 30.0;
    const bh = 50.0;
    // 막대.
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            Rect.fromCenter(center: Offset(cx, top), width: bw + 12, height: 5),
            const Radius.circular(3)),
        _fill(_dim(const Color(0xFFC29A76), boss)));
    final body = Path()
      ..moveTo(cx - bw / 2, top + 2)
      ..lineTo(cx + bw / 2, top + 2)
      ..lineTo(cx + bw / 2, top + bh - 6)
      ..quadraticBezierTo(cx + bw / 2, top + bh, cx + bw / 2 - 5, top + bh - 3)
      ..lineTo(cx, top + bh - 10)
      ..lineTo(cx - bw / 2 + 5, top + bh - 3)
      ..quadraticBezierTo(cx - bw / 2, top + bh, cx - bw / 2, top + bh - 6)
      ..close();
    canvas.drawPath(
        body.shift(const Offset(2, 3)), _fill(const Color(0x24283C50)));
    canvas.drawPath(
        body,
        Paint()
          ..shader = Gradient.linear(
              Offset(cx - bw / 2, 0),
              Offset(cx + bw / 2, 0),
              [_tint(accent, 0.05), _tint(accent, 0.25)]));
    canvas.drawRect(
        Rect.fromLTWH(cx - bw / 2, top + 8, bw, 3), _fill(_tint(accent, 0.65)));
    canvas.drawCircle(Offset(cx, top + 24), 7, _fill(_tint(accent, 0.8)));
  }

  void _sconce(double cx) {
    const cy = -60.0;
    canvas.drawCircle(Offset(cx, cy), 16, _fill(const Color(0x22FFD98A)));
    canvas.drawCircle(Offset(cx, cy), 9, _fill(const Color(0x33FFE6A8)));
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            Rect.fromCenter(center: Offset(cx, cy + 9), width: 12, height: 6),
            const Radius.circular(3)),
        _fill(_dim(const Color(0xFFC29A76), boss)));
    canvas.drawOval(
        Rect.fromCenter(center: Offset(cx, cy), width: 8, height: 11),
        _fill(const Color(0xFFFFD98A)));
  }

  /// 깔개가 닿는 안쪽 아치(보스가 나오는 곳).
  void _arch(double cx) {
    const aw = 80.0;
    const top = -78.0;
    Path arch(double inset) => Path()
      ..moveTo(cx - aw / 2 + inset, 0)
      ..lineTo(cx - aw / 2 + inset, top + aw / 2)
      ..arcToPoint(Offset(cx + aw / 2 - inset, top + aw / 2),
          radius: Radius.circular(aw / 2 - inset))
      ..lineTo(cx + aw / 2 - inset, 0)
      ..close();
    canvas.drawPath(arch(0), _fill(_mix(0xFFFBF8FD, 0.1)));
    final inner = arch(7);
    canvas.drawPath(
        inner,
        Paint()
          ..shader = Gradient.linear(Offset(cx, top), const Offset(0, 0), [
            _dim(Color.lerp(outsideOf(accent), accent, 0.32)!, boss),
            _dim(Color.lerp(outsideOf(accent), const Color(0xFFFFFFFF), 0.2)!,
                boss),
          ]));
    // 아치 꼭대기 장식(강조색 둥근 돌).
    canvas.drawCircle(Offset(cx, top + 3), 6, _fill(_tint(accent, 0.2)));
  }

  /// 벽이 바닥에 드리우는 부드러운 그림자.
  void _innerShadows() {
    const top = Rect.fromLTWH(0, 0, w, 28);
    canvas.drawRect(
        top,
        Paint()
          ..shader = Gradient.linear(top.topCenter, top.bottomCenter,
              const [Color(0x33283C50), Color(0x00283C50)]));
    for (final (x0, x1) in [(0.0, 12.0), (w, w - 12)]) {
      final r = Rect.fromLTRB(min(x0, x1), 0, max(x0, x1), w);
      canvas.drawRect(
          r,
          Paint()
            ..shader = Gradient.linear(Offset(x0, 0), Offset(x1, 0),
                const [Color(0x22283C50), Color(0x00283C50)]));
    }
  }

  /// 아래 문턱 가운데(출입구) 깔개.
  void _doormat() {
    const rect = Rect.fromLTRB(5 * s + 2, w - 6, 7 * s - 2, w + rimH + 10);
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            rect.shift(const Offset(0, 3)), const Radius.circular(10)),
        _fill(const Color(0x30000000)));
    canvas.drawRRect(RRect.fromRectAndRadius(rect, const Radius.circular(10)),
        _fill(_mix(0xFFE8C9A0, 0.15)));
    final stripes = Path();
    for (var i = 1; i < 4; i++) {
      stripes.addRRect(RRect.fromRectAndRadius(
          Rect.fromLTWH(
              rect.left + 8, rect.top + i * 7 - 1, rect.width - 16, 2.5),
          const Radius.circular(1.5)));
    }
    canvas.drawPath(stripes, _fill(_mix(0xFFCFA97E, 0.15)));
  }

  void _bossGlow() {
    const c = Offset(6 * s, -24);
    final p = Paint();
    for (var i = 0; i < 4; i++) {
      p.color = const Color(0xFFFF5F6D).withValues(alpha: 0.08);
      canvas.drawOval(
          Rect.fromCenter(
              center: c, width: 110.0 + i * 50, height: 70.0 + i * 30),
          p);
    }
  }
}

/// 방 안 돌기둥(받침·기둥몸·머리, 강조색 띠). 아래 변 y로 정렬해 뒤에 선 학생을 가린다.
class _Pillar extends PositionComponent
    with HasGameReference<FlameGame>, OffscreenCulling {
  _Pillar(int col, int row, this.accent)
      : super(
          position: Vector2((col + 0.5) * DungeonGame.grid.tile,
              (row + 1) * DungeonGame.grid.tile - 6),
          size: Vector2(40, 100),
          anchor: const Anchor(0.5, 0.98),
          priority: TopDownGrid.depth((row + 1) * DungeonGame.grid.tile - 6),
        );

  final Color accent;

  @override
  bool get cullsStatically => true;

  static const _plinth = Rect.fromLTWH(0, 84, 40, 14);
  static const _shaft = Rect.fromLTWH(6, 14, 28, 74);
  static const _head = Rect.fromLTWH(1, 3, 38, 14);

  static final _shadow = Paint()..color = const Color(0x33283C50);
  final _plinthPaint = Paint();
  final _shaftPaint = Paint();
  final _headPaint = Paint();
  final _bandPaint = Paint();
  final _flutePaint = Paint();

  void setBoss(bool boss) {
    final light =
        _dim(Color.lerp(const Color(0xFFF7F4FA), accent, 0.08)!, boss);
    final shade =
        _dim(Color.lerp(const Color(0xFFD3CBE0), accent, 0.14)!, boss);
    _plinthPaint.shader = Gradient.linear(
        _plinth.topCenter, _plinth.bottomCenter, [light, shade]);
    _shaftPaint.shader = Gradient.linear(_shaft.centerLeft, _shaft.centerRight,
        [light, Color.lerp(light, shade, 0.35)!, shade], [0, 0.45, 1]);
    _headPaint.shader =
        Gradient.linear(_head.topCenter, _head.bottomCenter, [light, shade]);
    _bandPaint.color =
        _dim(Color.lerp(accent, const Color(0xFFFFFFFF), 0.25)!, boss);
    _flutePaint.color = shade.withValues(alpha: 0.55);
  }

  @override
  void render(Canvas canvas) {
    canvas.drawOval(
        Rect.fromCenter(center: const Offset(22, 97), width: 50, height: 16),
        _shadow);
    canvas.drawRRect(
        RRect.fromRectAndRadius(_shaft, const Radius.circular(7)), _shaftPaint);
    for (final x in [14.0, 20.0, 26.0]) {
      canvas.drawRRect(
          RRect.fromRectAndRadius(
              Rect.fromLTWH(x - 1, 30, 2, 50), const Radius.circular(1)),
          _flutePaint);
    }
    canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(_shaft.left, 19, _shaft.width, 6),
            const Radius.circular(3)),
        _bandPaint);
    canvas.drawRRect(RRect.fromRectAndRadius(_plinth, const Radius.circular(7)),
        _plinthPaint);
    canvas.drawRRect(
        RRect.fromRectAndRadius(_head, const Radius.circular(7)), _headPaint);
  }
}
