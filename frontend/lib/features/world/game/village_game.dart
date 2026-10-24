import 'dart:math' as math;
import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';
import 'package:flame/game.dart';
import 'package:flame_svg/flame_svg.dart';
import 'package:flutter/painting.dart' show TextPainter, TextSpan, TextStyle;
import 'package:flutter/services.dart';
import 'package:flutter/material.dart' show Theme;
import 'package:flutter/widgets.dart' show KeyEventResult, visibleForTesting;

import '../state/game_models.dart';
import 'soft_terrain.dart';
import 'student_avatar.dart';
import 'top_down_grid.dart';
import 'village_map.dart';
import 'world_sprite.dart';

/// 건물 하나의 서버 상태.
typedef DungeonState = ({bool open, bool cleared, int clearCount});

/// 위에서 내려다본 마을. 학생이 길을 걸어 건물 문에 닿으면 [onDoorReached]를 부른다.
///
/// 던전 화면을 위에 띄우는 동안에는 호출하는 쪽이 이 게임에 `pauseEngine()`을
/// 부르고, 마을로 돌아오면 `resumeEngine()`을 부른다. 가려진 라우트에서도 게임
/// 루프는 계속 돌기 때문이다.
///
/// 게임 하나는 GameWidget 하나의 수명 동안만 쓴다. 위젯이 dispose되면
/// [onDispose]가 그림·SVG 캐시를 모두 놓는다.
class VillageGame extends FlameGame<TapWorld> with KeyboardEvents {
  VillageGame({
    this.onDoorReached,
    this.onFountainTapped,
    this.look = StudentLook.boy,
  }) : super(world: TapWorld());

  /// 주인공 그림(남·여).
  final StudentLook look;

  /// 열린 건물의 문 칸에 닿았을 때. 공사 중(open=false)인 건물은 부르지 않는다.
  void Function(String dungeonId)? onDoorReached;

  /// 분수 칸이나 그 주변 8칸을 눌렀을 때(랭킹 게시판).
  void Function()? onFountainTapped;

  /// 지금 눌려 있는 키. 포커스를 잃어 KeyUp을 못 받아도 누름 상태를 바로잡는 데
  /// 쓴다. 테스트에서만 바꾼다.
  @visibleForTesting
  Set<LogicalKeyboardKey> Function() pressedKeys =
      () => HardwareKeyboard.instance.logicalKeysPressed;

  static const grid = TopDownGrid();

  /// 맵 전체의 월드 크기(정사각).
  static double get mapExtent => VillageMap.size * grid.tile;

  /// 가로등을 세우는 잔디 칸(길 옆).
  static const lamps = [(7, 8), (16, 8), (7, 15), (16, 15)];

  /// 흰 울타리를 두르는 칸(한 줄, 장식).
  static const fenceTiles = [(2, 8), (3, 8), (4, 8), (5, 8), (6, 8), (7, 8)];

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
  final _buildings = <String, _Building>{};
  late final _TargetMarker _marker;
  final _tiles = VillageTerrain(grid);

  Map<String, DungeonState> _state = const {};
  bool _graduated = false;
  (int, int)? _heldDir;

  /// 건물과 그 이름 칩의 그리기 순서.
  @visibleForTesting
  ({int building, int label}) depthOf(String id) {
    final b = _buildings[id]!;
    return (building: b.priority, label: b.label.priority);
  }

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
  Color backgroundColor() => const Color(0xFF9DDC85);

  @override
  Future<void> onLoad() async {
    await _bank.loadAll(this, [
      'tree',
      'fountain',
      'lamp',
      'flag',
      for (final id in VillageMap.buildings.keys) 'building_$id',
    ]);
    // 읽는 사이에 게임이 dispose됐으면(마을을 떠남) 더 만들지 않는다.
    if (_bank.isDisposed) return;

    world.onTap = _onTapWorld;
    world.add(_tiles);
    world.add(_marker = _TargetMarker(grid));

    // 소품은 장식만 한다. 걷기 판정은 VillageMap을 따른다.
    // 그리기 순서는 아래 변 y: 위쪽 칸에 선 학생은 소품·건물에 가려진다.
    final t = grid.tile;
    WorldSprite prop(String name, double x, double bottom, Vector2 size,
            {double? depthY}) =>
        WorldSprite(
          _bank[name],
          position: Vector2(x, bottom),
          size: size,
          anchor: Anchor.bottomCenter,
          priority: TopDownGrid.depth(depthY ?? bottom),
        );
    for (var r = 0; r < VillageMap.size; r++) {
      for (var c = 0; c < VillageMap.size; c++) {
        if (VillageMap.tileAt(c, r) == 'T') {
          // 나무 칸에는 설 수 없으니 칸 가운데로 정렬해도 앞뒤가 같다. 그래야
          // 바로 아래 건물의 이름 칩이 나무 위에 온다.
          world.add(prop('tree', (c + 0.5) * t, (r + 1) * t, Vector2(48, 60),
              depthY: (r + 0.5) * t));
        }
      }
    }
    final (fc, fr) = VillageMap.fountainTile;
    world
        .add(prop('fountain', (fc + 0.5) * t, (fr + 2) * t, Vector2(144, 120)));
    for (final (c, r) in lamps) {
      world.add(prop('lamp', (c + 0.8) * t, (r + 0.9) * t, Vector2(24, 72)));
    }
    world.add(_Fence(grid, fenceTiles));

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
      look: look,
      onTileReached: _onTileReached,
    )..graduated = _graduated;
    world.add(avatar);

    camera.viewfinder.anchor = Anchor.center;
    _avatarReady = true;
    _followAvatar();
  }

  final _cameraTarget = Vector2.zero();

  /// [avatar]가 만들어졌다. 읽는 중에 떠나면 끝까지 false다.
  bool _avatarReady = false;

  /// 카메라를 주인공에 맞추되 맵 밖이 보이지 않게 가장자리에서 멈춘다.
  /// 보이는 폭이 맵보다 넓으면 그 축은 맵 가운데에 둔다.
  void _followAvatar() {
    if (!_avatarReady) return;
    final zoom = camera.viewfinder.zoom;
    final view = camera.viewport.virtualSize;
    final extent = mapExtent;
    double clampAxis(double v, double h) =>
        h * 2 >= extent ? extent / 2 : v.clamp(h, extent - h);
    // 매 프레임 불리므로 목표 벡터를 새로 만들지 않고 고쳐 쓴다.
    camera.viewfinder.position = _cameraTarget
      ..setValues(
        clampAxis(avatar.position.x, view.x / (2 * zoom)),
        clampAxis(avatar.position.y, view.y / (2 * zoom)),
      );
  }

  @override
  void onGameResize(Vector2 size) {
    super.onGameResize(size);
    // 짧은 변에 7칸이 보이게 하되 한 칸은 화면에서 48~76px로 둔다.
    // 폰은 짧은 변 7칸, 태블릿·데스크톱은 76px 칸으로 더 넓게 보인다.
    final tile = (math.min(size.x, size.y) / 7).clamp(48.0, 76.0);
    camera.viewfinder.zoom = tile / grid.tile;
    _followAvatar();
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
    _followAvatar();
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

/// 탭한 목적지 칸 표시.
class _TargetMarker extends Component {
  _TargetMarker(this.grid) : super(priority: -0x7fffff0);

  final TopDownGrid grid;
  (int, int)? _goal;
  RRect _box = RRect.zero;
  (int, int)? get goal => _goal;
  set goal((int, int)? g) {
    if (g == _goal) return;
    _goal = g;
    if (g == null) return;
    final c = grid.toScreen(g.$1, g.$2);
    _box = RRect.fromRectAndRadius(
      Rect.fromCenter(
          center: Offset(c.x, c.y),
          width: grid.tile * 0.78,
          height: grid.tile * 0.78),
      Radius.circular(grid.tile * 0.22),
    );
  }

  final _paint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 2.5
    ..color = const Color(0xCC6C8CFF);

  @override
  void render(Canvas canvas) {
    if (_goal != null) canvas.drawRRect(_box, _paint);
  }
}

/// 흰 울타리 한 줄(장식). 아래 변 y로 정렬해 그 칸에 선 학생 다리를 가린다.
class _Fence extends PositionComponent
    with HasGameReference<VillageGame>, OffscreenCulling<VillageGame> {
  _Fence(TopDownGrid grid, List<(int, int)> tiles) {
    final t = grid.tile;
    final u = t / 32;
    final parts = <RRect>[];
    var left = double.infinity, right = -double.infinity, top = 0.0;
    for (final (c, r) in tiles) {
      final x = c * t, y = r * t + 8 * u;
      top = y;
      left = left < x ? left : x;
      right = right > x + t ? right : x + t;
      for (final rail in [
        Rect.fromLTWH(x, y + 6 * u, t, 3 * u),
        Rect.fromLTWH(x, y + 13 * u, t, 3 * u),
        Rect.fromLTWH(x + 3 * u, y + u, 4 * u, 20 * u),
      ]) {
        parts.add(RRect.fromRectAndRadius(rail, Radius.circular(1.5 * u)));
      }
    }
    // 끝 기둥.
    parts.add(RRect.fromRectAndRadius(
        Rect.fromLTWH(right - 4 * u, top + u, 4 * u, 20 * u),
        Radius.circular(1.5 * u)));
    position = Vector2(left, top);
    size = Vector2(right - left, 23 * u);
    // 그림은 생성 때 한 번만 Path 둘(나무·그림자)로 합친다.
    for (final p in parts) {
      _wood.addRRect(p.shift(Offset(-left, -top)));
      _shadow.addRRect(p.shift(Offset(-left, 2 - top)));
    }
    priority = TopDownGrid.depth(top + size.y);
  }

  final _wood = Path();
  final _shadow = Path();

  @override
  bool get cullsStatically => true;

  static final _shadowPaint = Paint()..color = const Color(0x1F000000);
  static final _woodPaint = Paint()..color = const Color(0xFFFFFFFF);

  @override
  void render(Canvas canvas) {
    canvas.drawPath(_shadow, _shadowPaint);
    canvas.drawPath(_wood, _woodPaint);
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
          // 그림(144×168) 아래로 문 앞 칸의 표지판 자리를 더 잡는다.
          size: Vector2(_art.x, _art.y + _signRoom),
          anchor: Anchor.bottomCenter,
        ) {
    final (c0, r0) = origin;
    const f = VillageMap.footprint;
    final t = VillageGame.grid.tile;
    // 그림 바닥 = 3×3 점유칸 아래 변 가운데.
    final bottom = (r0 + f) * t;
    position = Vector2((c0 + f / 2) * t, bottom + _signRoom);
    // 아래 변 y: 문 칸(아래 줄)에 선 학생은 앞에, 건물 위쪽 칸의 학생은 뒤에 그린다.
    priority = TopDownGrid.depth(bottom);
    label = _BuildingLabel(this);
    // 문 왼쪽 칸에 꽂힌 작은 표지판(건물 로컬 좌표).
    final doorX = (door.$1 + 0.5) * t - c0 * t;
    _signBase = Offset(doorX - t * 0.85, _art.y + t * 0.42);
    _signPost = Rect.fromLTWH(_signBase.dx - 1.5, _signBase.dy - 14, 3, 14);
  }

  /// 건물 그림 크기(에셋 계약).
  static final _art = Vector2(144, 168);
  static const _signRoom = 24.0;

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

  static final _flagSize = Vector2(24, 36);

  /// 깃발을 꽂을 지붕 위 자리(건물 SVG 좌표, 깃발 왼쪽 위).
  static const flagSpots = {
    'k8s': (60.0, -4.0),
    'jvm': (104.0, -14.0),
    'db': (106.0, -10.0),
    'net': (60.0, -4.0),
    'build': (100.0, -4.0),
    'ops': (32.0, 8.0),
    'ai': (108.0, 0.0),
    'shell': (100.0, 4.0),
  };

  @override
  void render(Canvas canvas) {
    svg.render(canvas, _art, overridePaint: _paint);
    final s = _state;
    if (s == null) return;
    if (s.cleared) {
      final (fx, fy) = flagSpots[id]!;
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

/// 건물 위 이름 칩. 바로 위 칸의 나무보다 앞에, 그 칸에 선 학생보다는 뒤에
/// 그린다(칩이 학생을 가리지 않게).
class _BuildingLabel extends PositionComponent
    with HasGameReference<VillageGame>, OffscreenCulling<VillageGame> {
  _BuildingLabel(this.building)
      : super(
          anchor: Anchor.bottomCenter,
          priority:
              TopDownGrid.depth(building.position.y - building.size.y) + 1,
        );

  final _Building building;
  TextPainter? _name;

  @override
  bool get cullsStatically => true;
  TextPainter? _closed;

  /// 건물 SVG(높이 168)에서 그림이 시작되는 y.
  static const _contentTop = {
    'k8s': 0.0,
    'jvm': 2.0,
    'db': 8.0,
    'net': 0.0,
    'build': 2.0,
    'ops': 2.0,
    'ai': 30.0,
    'shell': 22.0,
  };

  static final _chip = Paint()..color = const Color(0xFFFFFFFF);
  static final _chipShadow = Paint()..color = const Color(0x1F3B4A6B);
  static final _closedChip = Paint()..color = const Color(0xFFFFB347);

  @override
  void onMount() {
    super.onMount();
    refresh();
    // onMount 중에는 isMounted가 아직 false라 refresh가 영역을 다시 재지 않는다.
    refreshCullBounds();
  }

  // onMount 중에도(isMounted 전) 앱 글꼴을 쓰도록 부모가 있으면 게임에서 고른다.
  TextStyle _style(double size, Color color) => parent != null
      ? game.labelStyle(size, color)
      : TextStyle(fontSize: size, fontWeight: FontWeight.w600, color: color);

  void refresh() {
    final s = building.state;
    // 건물 그림에서 가장 높은 곳(깃대, 연기, 안테나, 꽂은 깃발) 바로 위.
    var top = _contentTop[building.id] ?? 0;
    if (s?.cleared == true) {
      top = math.min(top, _Building.flagSpots[building.id]!.$2);
    }
    position = building.position - Vector2(0, building.size.y - top + 4);
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
