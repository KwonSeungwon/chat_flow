import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';
import 'package:flame/game.dart';
import 'package:flame_svg/flame_svg.dart';
import 'package:flutter/painting.dart' show TextPainter, TextSpan, TextStyle;
import 'package:flutter/services.dart';
import 'package:flutter/material.dart' show Theme;
import 'package:flutter/widgets.dart' show KeyEventResult, visibleForTesting;

import 'iso_grid.dart';
import 'student_avatar.dart';
import 'village_map.dart';
import 'world_sprite.dart';

/// 건물 하나의 서버 상태.
typedef DungeonState = ({bool open, bool cleared, int clearCount});

/// 아이소메트릭 마을. 학생이 길을 걸어 건물 문에 닿으면 [onDoorReached]를 부른다.
///
/// 던전 화면을 위에 띄우는 동안에는 호출하는 쪽이 이 게임에 `pauseEngine()`을
/// 부르고, 마을로 돌아오면 `resumeEngine()`을 부른다. 가려진 라우트에서도 게임
/// 루프는 계속 돌기 때문이다.
///
/// 게임 하나는 GameWidget 하나의 수명 동안만 쓴다. 위젯이 dispose되면
/// [onDispose]가 그림·SVG 캐시를 모두 놓는다.
class VillageGame extends FlameGame<TapWorld> with KeyboardEvents {
  VillageGame({this.onDoorReached, this.onFountainTapped})
      : super(world: TapWorld());

  /// 열린 건물의 문 칸에 닿았을 때. 공사 중(open=false)인 건물은 부르지 않는다.
  void Function(String dungeonId)? onDoorReached;

  /// 분수 칸이나 그 주변 8칸을 눌렀을 때(랭킹 게시판).
  void Function()? onFountainTapped;

  /// 지금 눌려 있는 키. 포커스를 잃어 KeyUp을 못 받아도 누름 상태를 바로잡는 데
  /// 쓴다. 테스트에서만 바꾼다.
  @visibleForTesting
  Set<LogicalKeyboardKey> Function() pressedKeys =
      () => HardwareKeyboard.instance.logicalKeysPressed;

  static const grid = IsoGrid(tileW: 64, tileH: 32);

  static const names = {
    'k8s': '쿠버네티스 성',
    'jvm': 'JVM 대장간',
    'db': 'DB 도서관',
    'net': '네트워크 요새',
    'build': '빌드 공방',
    'ops': '운영 관제탑',
    'ai': 'AI 연구소',
    'shell': '셸 동굴',
  };

  late final StudentAvatar avatar;
  final _bank = SvgBank();
  final _sky = SkyBackdrop(const Color(0xFFE9F1FF), const Color(0xFFFDF3E7));
  final _buildings = <String, _Building>{};
  late final _TargetMarker _marker;
  final _tiles = _VillageTiles();

  Map<String, DungeonState> _state = const {};
  bool _graduated = false;
  (int, int)? _heldDir;

  /// 건물별 상태를 반영한다(깃발, 클리어 인원 표지판, 공사 중).
  void setDungeonState(Map<String, DungeonState> state) {
    _state = Map.of(state);
    if (!isLoaded) return;
    for (final e in _buildings.entries) {
      e.value.state = _state[e.key];
    }
  }

  void setGraduated(bool graduated) {
    _graduated = graduated;
    if (isLoaded) avatar.graduated = graduated;
  }

  /// 캔버스 글자 스타일. 앱 테마의 글꼴(Noto Sans KR)을 따른다. 기본 글꼴로 그리면
  /// CanvasKit에서 한글이 □로 남는다. 위젯에 붙기 전에는 기본 글꼴.
  TextStyle labelStyle(double size, Color color) {
    final context = buildContext;
    final family = context == null
        ? null
        : Theme.of(context).textTheme.bodyMedium?.fontFamily;
    return TextStyle(
      fontFamily: family,
      fontSize: size,
      fontWeight: FontWeight.w600,
      color: color,
    );
  }

  @override
  Color backgroundColor() => const Color(0xFFE9F1FF);

  @override
  Future<void> onLoad() async {
    await _bank.loadAll(this, [
      'tree',
      'fountain',
      'flag',
      for (final id in VillageMap.buildings.keys) 'building_$id',
    ]);
    // 읽는 사이에 게임이 dispose됐으면(마을을 떠남) 더 만들지 않는다.
    if (_bank.isDisposed) return;

    world.onTap = _onTapWorld;
    world.add(_tiles);
    world.add(_marker = _TargetMarker(grid));

    for (var r = 0; r < VillageMap.size; r++) {
      for (var c = 0; c < VillageMap.size; c++) {
        if (VillageMap.tileAt(c, r) == 'T') {
          world.add(WorldSprite(
            _bank['tree'],
            position: grid.toScreen(c, r) + Vector2(0, 6),
            size: Vector2(44, 66),
            anchor: Anchor.bottomCenter,
            priority: isoDepth((c + r).toDouble()),
          ));
        }
      }
    }

    final (fc, fr) = VillageMap.fountainTile;
    world.add(WorldSprite(
      _bank['fountain'],
      position: grid.toScreen(fc, fr) + Vector2(0, 22),
      size: Vector2(96, 80),
      anchor: Anchor.bottomCenter,
      priority: isoDepth((fc + fr).toDouble()),
    ));

    for (final MapEntry(key: id, value: origin)
        in VillageMap.buildings.entries) {
      final b = _Building(
        id: id,
        name: names[id]!,
        origin: origin,
        door: VillageMap.doors[id]!,
        svg: _bank['building_$id'],
        flag: _bank['flag'],
      )..state = _state[id];
      _buildings[id] = b;
      world.add(b);
      world.add(b.label);
    }

    avatar = StudentAvatar(
      grid: grid,
      tile: VillageMap.startTile,
      onTileReached: _onTileReached,
    )..graduated = _graduated;
    world.add(avatar);

    camera.viewfinder.anchor = Anchor.center;
    camera.follow(avatar, maxSpeed: 900);
  }

  @override
  void onGameResize(Vector2 size) {
    super.onGameResize(size);
    _sky.resize(size.toSize());
    // 폰 폭(400px)에서는 9칸 남짓, 넓은 화면에서는 마을 전체가 보이게.
    camera.viewfinder.zoom = (size.x / 1000).clamp(0.55, 1.0);
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
    _tiles.release();
    if (isLoaded) avatar.releaseAssets();
    _bank.dispose();
    super.onDispose();
  }

  @override
  void onDetach() {
    _heldDir = null;
    super.onDetach();
  }

  @override
  void update(double dt) {
    // KeyUp을 못 받은 채 키가 떼어졌으면(포커스 이동) 누름 상태를 버린다.
    if (_heldDir != null && directionFromKeys(pressedKeys()) != _heldDir) {
      _heldDir = null;
    }
    final dir = _heldDir;
    if (dir != null && !avatar.isMoving) {
      final (c, r) = avatar.tile;
      final next = (c + dir.$1, r + dir.$2);
      if (VillageMap.isWalkable(next.$1, next.$2)) {
        _marker.goal = null;
        avatar.moveAlong([next]);
      }
    }
    super.update(dt);
  }

  @override
  KeyEventResult onKeyEvent(
      KeyEvent event, Set<LogicalKeyboardKey> keysPressed) {
    _heldDir = directionFromKeys(keysPressed);
    return _heldDir != null || movementKeys.contains(event.logicalKey)
        ? KeyEventResult.handled
        : KeyEventResult.ignored;
  }

  void _onTapWorld(Vector2 worldPos) {
    var goal = grid.toTile(worldPos);
    final (fc, fr) = VillageMap.fountainTile;
    if ((goal.$1 - fc).abs() <= 1 && (goal.$2 - fr).abs() <= 1) {
      onFountainTapped?.call();
      return;
    }
    // 건물을 누르면 그 건물 문으로 간다.
    final building = VillageMap.buildingAt(goal.$1, goal.$2);
    if (building != null) goal = VillageMap.doors[building]!;
    if (!VillageMap.isWalkable(goal.$1, goal.$2)) return;
    // 목표가 아닌 문은 지나가지 않는다. 지나가면 엉뚱한 던전에 들어간다.
    bool walkable(int c, int r) =>
        VillageMap.isWalkable(c, r) &&
        (VillageMap.doorAt(c, r) == null || (c, r) == goal);
    final path = grid.findPath(avatar.tile, goal, walkable);
    if (path.isEmpty) return;
    _marker.goal = goal;
    avatar.moveAlong(path);
  }

  void _onTileReached(int col, int row) {
    if (_marker.goal == (col, row)) _marker.goal = null;
    final id = VillageMap.doorAt(col, row);
    if (id == null) return;
    avatar.stop();
    _heldDir = null;
    if (_state[id]?.open == false) return;
    onDoorReached?.call(id);
  }
}

/// 이동에 쓰는 키(방향키, WASD).
final movementKeys = {
  LogicalKeyboardKey.arrowUp,
  LogicalKeyboardKey.arrowDown,
  LogicalKeyboardKey.arrowLeft,
  LogicalKeyboardKey.arrowRight,
  LogicalKeyboardKey.keyW,
  LogicalKeyboardKey.keyA,
  LogicalKeyboardKey.keyS,
  LogicalKeyboardKey.keyD,
};

/// 방향키·WASD → 격자 방향. 화면 위쪽 키가 row-1(오른쪽 위)로 간다.
(int, int)? directionFromKeys(Set<LogicalKeyboardKey> keys) {
  bool any(LogicalKeyboardKey a, LogicalKeyboardKey b) =>
      keys.contains(a) || keys.contains(b);
  if (any(LogicalKeyboardKey.arrowUp, LogicalKeyboardKey.keyW)) return (0, -1);
  if (any(LogicalKeyboardKey.arrowDown, LogicalKeyboardKey.keyS)) return (0, 1);
  if (any(LogicalKeyboardKey.arrowLeft, LogicalKeyboardKey.keyA)) {
    return (-1, 0);
  }
  if (any(LogicalKeyboardKey.arrowRight, LogicalKeyboardKey.keyD)) {
    return (1, 0);
  }
  return null;
}

/// 마을 타일 전체를 한 번 Picture로 구워 매 프레임 재생만 한다.
class _VillageTiles extends Component {
  _VillageTiles() : super(priority: -0x7ffffff);

  Picture? _picture;

  static const _grassA = Color(0xFF9FD8A8);
  static const _grassB = Color(0xFFA7DCB0);
  static const _grassLeft = Color(0xFF6FB487);
  static const _grassRight = Color(0xFF5A9D74);
  static const _path = Color(0xFFF3E6C8);
  static const _door = Color(0xFFE8D6AE);
  static const _plaza = Color(0xFFE8EDF5);
  static const _water = Color(0xFF7CC4F2);
  static const _waterLeft = Color(0xFF5AA9DC);
  static const _waterRight = Color(0xFF4B93C4);

  @override
  Future<void> onLoad() async {
    final baker = IsoTileBaker(VillageGame.grid);
    const n = VillageMap.size;
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        final t = VillageMap.tileAt(c, r)!;
        final plaza = c >= 9 && c <= 13 && r >= 9 && r <= 13;
        final top = switch (t) {
          'P' || 'S' => plaza ? _plaza : _path,
          'F' => _plaza,
          'D' => _door,
          'W' => _water,
          _ => (c + r).isEven ? _grassA : _grassB,
        };
        baker.addTile(c, r, top);
        if (r == n - 1) {
          baker.addLeftSide(c, r, t == 'W' ? _waterLeft : _grassLeft);
        }
        if (c == n - 1) {
          baker.addRightSide(c, r, t == 'W' ? _waterRight : _grassRight);
        }
      }
    }
    final recorder = PictureRecorder();
    final canvas = Canvas(recorder);
    baker.paintShadow(canvas, n, n, const Color(0xFF3B4A6B));
    baker.paintFaces(canvas, outline: const Color(0x0F3B4A6B));
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

/// 탭한 목적지 칸 표시.
class _TargetMarker extends Component {
  _TargetMarker(this.grid) : super(priority: -0x7fffff0);

  final IsoGrid grid;
  (int, int)? _goal;
  (int, int)? get goal => _goal;
  set goal((int, int)? g) {
    if (g == _goal) return;
    _goal = g;
    if (g == null) return;
    final c = grid.toScreen(g.$1, g.$2);
    final hw = grid.halfW * 0.8, hh = grid.halfH * 0.8;
    _path
      ..reset()
      ..addPolygon([
        Offset(c.x, c.y - hh),
        Offset(c.x + hw, c.y),
        Offset(c.x, c.y + hh),
        Offset(c.x - hw, c.y),
      ], true);
  }

  final _paint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 2.5
    ..color = const Color(0xCC6C8CFF);
  final _path = Path();

  @override
  void render(Canvas canvas) {
    if (_goal != null) canvas.drawPath(_path, _paint);
  }
}

/// 3×3 칸을 차지하는 건물. 깃발, 클리어 인원 표지판, 공사 중 반투명을 그린다.
class _Building extends PositionComponent
    with HasGameReference<VillageGame>, OffscreenCulling<VillageGame> {
  _Building({
    required this.id,
    required this.name,
    required (int, int) origin,
    required (int, int) door,
    required this.svg,
    required this.flag,
  }) : super(
          size: Vector2(192, 224),
          anchor: Anchor.bottomCenter,
        ) {
    final (c0, r0) = origin;
    const f = VillageMap.footprint;
    const grid = VillageGame.grid;
    // 점유 영역 앞 모서리(가장 아래 꼭짓점)에 바닥을 맞춘다.
    position = grid.toScreen(c0 + f - 1, r0 + f - 1) + Vector2(0, grid.halfH);
    // 가운데 칸보다 살짝 뒤: 문 앞이나 옆에 선 학생이 건물 위에 그려진다.
    priority = isoDepth((c0 + 1 + r0 + 1) - 0.5);
    label = _BuildingLabel(this);
    // 문 왼쪽 앞에 꽂힌 작은 표지판(건물 로컬 좌표).
    final d = grid.toScreen(door.$1, door.$2);
    final local = d - (position - Vector2(size.x / 2, size.y));
    _signBase = Offset(local.x - 30, local.y + 4);
    _signPost = Rect.fromLTWH(_signBase.dx - 1.5, _signBase.dy - 16, 3, 16);
  }

  final String id;
  final String name;
  final Svg svg;
  final Svg flag;
  late final _BuildingLabel label;
  late final Offset _signBase;
  late final Rect _signPost;
  RRect? _signBoard;
  Offset _signTextAt = Offset.zero;

  @override
  bool get cullsStatically => true;

  DungeonState? _state;
  DungeonState? get state => _state;
  set state(DungeonState? s) {
    _state = s;
    _paint = (s != null && !s.open) ? _closedPaint : null;
    label.refresh();
  }

  Paint? _paint;
  static final _closedPaint = Paint()..color = const Color(0x73FFFFFF);
  static final _postPaint = Paint()..color = const Color(0xFF8A6A4A);
  static final _boardPaint = Paint()..color = const Color(0xFFFFF8EE);
  static final _boardEdge = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 1.5
    ..color = const Color(0xFFC9B48A);

  TextPainter? _signText;

  static final _flagSize = Vector2(30, 45);

  /// 깃발을 꽂을 지붕 위 자리(건물 SVG 좌표, 깃발 왼쪽 위).
  static const _flagSpots = {
    'k8s': (124.0, 44.0),
    'jvm': (56.0, 58.0),
    'db': (122.0, 56.0),
    'net': (128.0, 70.0),
    'build': (124.0, 64.0),
    'ops': (120.0, 12.0),
    'ai': (126.0, 62.0),
    'shell': (118.0, 46.0),
  };

  @override
  void render(Canvas canvas) {
    svg.render(canvas, size, overridePaint: _paint);
    final s = _state;
    if (s == null) return;
    if (s.cleared) {
      final (fx, fy) = _flagSpots[id]!;
      canvas.save();
      canvas.translate(fx, fy);
      flag.render(canvas, _flagSize);
      canvas.restore();
    }
    _renderSign(canvas, s.clearCount);
  }

  int? _signCount;

  void _renderSign(Canvas canvas, int count) {
    if (_signCount != count || _signText == null) {
      _signCount = count;
      final tp = _signText = TextPainter(
        text: TextSpan(
          text: '$count명',
          style: game.labelStyle(10, const Color(0xFF3B4A6B)),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      final board = _signBoard = RRect.fromRectAndRadius(
        Rect.fromCenter(
            center: _signBase.translate(0, -20),
            width: tp.width + 12,
            height: tp.height + 6),
        const Radius.circular(4),
      );
      _signTextAt = board.center - Offset(tp.width / 2, tp.height / 2);
    }
    canvas.drawRect(_signPost, _postPaint);
    canvas.drawRRect(_signBoard!, _boardPaint);
    canvas.drawRRect(_signBoard!, _boardEdge);
    _signText!.paint(canvas, _signTextAt);
  }
}

/// 건물 위 이름 칩. 모든 것 위에 그려지도록 따로 둔다.
class _BuildingLabel extends PositionComponent
    with HasGameReference<VillageGame>, OffscreenCulling<VillageGame> {
  _BuildingLabel(this.building)
      : super(anchor: Anchor.bottomCenter, priority: 0x7ffffff);

  final _Building building;
  TextPainter? _name;

  @override
  bool get cullsStatically => true;
  TextPainter? _closed;

  /// 건물 SVG(높이 224)에서 그림이 시작되는 y.
  static const _contentTop = {
    'k8s': 6.0,
    'jvm': 4.0,
    'db': 40.0,
    'net': 36.0,
    'build': 64.0,
    'ops': 0.0,
    'ai': 30.0,
    'shell': 60.0,
  };

  static final _chip = Paint()..color = const Color(0xFFFFFFFF);
  static final _chipShadow = Paint()..color = const Color(0x1F3B4A6B);
  static final _closedChip = Paint()..color = const Color(0xFFFFB347);

  @override
  void onMount() {
    super.onMount();
    // 건물 SVG에서 가장 높은 곳(깃대, 연기, 안테나) 바로 위.
    final top = _contentTop[building.id] ?? 0;
    position = building.position - Vector2(0, building.size.y - top + 6);
    refresh();
  }

  TextStyle _style(double size, Color color) => isMounted
      ? game.labelStyle(size, color)
      : TextStyle(fontSize: size, fontWeight: FontWeight.w600, color: color);

  void refresh() {
    final s = building.state;
    _name = TextPainter(
      text: TextSpan(
        text: s?.cleared == true ? '${building.name} ✓' : building.name,
        style: _style(12, const Color(0xFF3B4A6B)),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    _closed = (s != null && !s.open)
        ? (TextPainter(
            text: TextSpan(
              text: '공사 중',
              style: _style(11, const Color(0xFFFFFFFF)),
            ),
            textDirection: TextDirection.ltr,
          )..layout())
        : null;
    final w = _name!.width + 24;
    size = Vector2(w, _closed == null ? 24 : 48);
    if (isMounted) refreshCullBounds();
  }

  @override
  void render(Canvas canvas) {
    final name = _name;
    if (name == null) return;
    final chip = RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 0, size.x, 24), const Radius.circular(12));
    canvas.drawRRect(chip.shift(const Offset(0, 3)), _chipShadow);
    canvas.drawRRect(chip, _chip);
    name.paint(
        canvas, Offset((size.x - name.width) / 2, (24 - name.height) / 2));
    final closed = _closed;
    if (closed != null) {
      final w = closed.width + 18;
      final c = RRect.fromRectAndRadius(
          Rect.fromLTWH((size.x - w) / 2, 28, w, 20),
          const Radius.circular(10));
      canvas.drawRRect(c, _closedChip);
      closed.paint(canvas,
          Offset((size.x - closed.width) / 2, 28 + (20 - closed.height) / 2));
    }
  }
}
