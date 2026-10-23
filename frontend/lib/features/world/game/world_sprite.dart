import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';
import 'package:flame/game.dart';
import 'package:flame_svg/flame_svg.dart';

/// 카메라 밖에 있는 컴포넌트는 그리지 않는다. 월드 직속 자식에 쓴다.
mixin OffscreenCulling<T extends FlameGame>
    on PositionComponent, HasGameReference<T> {
  /// true면 붙을 때 한 번 잰 영역을 계속 쓴다(움직이지 않는 컴포넌트).
  /// 크기·위치가 바뀌면 [refreshCullBounds]를 부른다.
  bool get cullsStatically => false;

  Rect? _bounds;

  void refreshCullBounds() => _bounds = toAbsoluteRect();

  @override
  void onMount() {
    super.onMount();
    if (cullsStatically) refreshCullBounds();
  }

  @override
  void renderTree(Canvas canvas) {
    final bounds =
        cullsStatically ? (_bounds ?? toAbsoluteRect()) : toAbsoluteRect();
    if (!game.camera.visibleWorldRect.overlaps(bounds)) return;
    super.renderTree(canvas);
  }
}

/// 여러 컴포넌트가 함께 쓰는 [Svg]를 고정 크기로 그린다. Svg는 게임이 소유하므로
/// 컴포넌트가 지워질 때 dispose하지 않는다(flame_svg의 SvgComponent와 다른 점).
class WorldSprite extends PositionComponent
    with HasGameReference<FlameGame>, OffscreenCulling {
  WorldSprite(
    this.svg, {
    super.position,
    super.size,
    super.anchor,
    super.priority,
  });

  Svg svg;
  Paint? paint;

  @override
  bool get cullsStatically => true;

  @override
  void render(Canvas canvas) => svg.render(canvas, size, overridePaint: paint);
}

/// 게임 하나가 쓰는 SVG를 한 번씩만 읽어 두고 게임이 끝날 때 한꺼번에 비운다.
class SvgBank {
  final _svgs = <String, Svg>{};
  bool _disposed = false;

  /// [dispose]가 불렸다. 그 뒤에 끝난 읽기는 바로 놓는다.
  bool get isDisposed => _disposed;

  int get length => _svgs.length;

  Future<void> loadAll(FlameGame game, Iterable<String> names) async {
    await Future.wait(names.map((n) async {
      final svg = await game.loadSvg('world/$n.svg');
      if (_disposed) {
        svg.dispose();
        svg.pictureInfo.picture.dispose();
        return;
      }
      _svgs[n] = svg;
    }));
  }

  Svg operator [](String name) => _svgs[name]!;

  void dispose() {
    _disposed = true;
    for (final s in _svgs.values) {
      s.dispose();
      s.pictureInfo.picture.dispose();
    }
    _svgs.clear();
  }
}

/// 탭을 월드 좌표로 넘겨 주는 월드.
class TapWorld extends World with TapCallbacks {
  void Function(Vector2 worldPos)? onTap;

  @override
  void onTapUp(TapUpEvent event) => onTap?.call(event.localPosition);
}
