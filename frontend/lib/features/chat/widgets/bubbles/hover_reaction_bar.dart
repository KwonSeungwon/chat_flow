import 'package:flutter/material.dart';

/// 웹(마우스) 호버 시 메시지 위에 뜨는 액션 툴바.
/// 빠른 반응 이모지 + (선택) 답글 + (선택) 더보기(⋯) 버튼을 한 줄로 보여준다.
/// long-press/우클릭에 숨어 있던 액션을 마우스 호버만으로 발견·실행할 수 있게 한다.
class HoverReactionBar extends StatelessWidget {
  final List<String> reactions;
  final void Function(String emoji)? onReaction;
  final VoidCallback? onReply;

  /// ⋯ 더보기 — 버튼의 전역 좌표를 넘겨 그 위치에 팝업 메뉴를 띄운다.
  final void Function(Offset globalPosition)? onMore;

  const HoverReactionBar({
    super.key,
    required this.reactions,
    this.onReaction,
    this.onReply,
    this.onMore,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final children = <Widget>[];

    if (onReaction != null) {
      for (final emoji in reactions) {
        children.add(GestureDetector(
          onTap: () => onReaction!(emoji),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 3),
            child: Text(emoji, style: const TextStyle(fontSize: 18)),
          ),
        ));
      }
    }

    final hasActionButtons = onReply != null || onMore != null;
    if (onReaction != null && hasActionButtons) {
      children.add(Container(
        width: 1,
        height: 18,
        margin: const EdgeInsets.symmetric(horizontal: 5),
        color: cs.outline.withAlpha(90),
      ));
    }

    if (onReply != null) {
      children.add(_ActionButton(
        icon: Icons.reply,
        tooltip: '답글',
        color: cs.onSurfaceVariant,
        onTapDown: (_) => onReply!(),
      ));
    }

    if (onMore != null) {
      children.add(_ActionButton(
        icon: Icons.more_horiz,
        tooltip: '더보기',
        color: cs.onSurfaceVariant,
        onTapDown: (d) => onMore!(d.globalPosition),
      ));
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [BoxShadow(color: Colors.black.withAlpha(30), blurRadius: 6, offset: const Offset(0, 2))],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: children,
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final Color color;
  final GestureTapDownCallback onTapDown;

  const _ActionButton({
    required this.icon,
    required this.tooltip,
    required this.color,
    required this.onTapDown,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTapDown: onTapDown,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Icon(icon, size: 18, color: color),
        ),
      ),
    );
  }
}
