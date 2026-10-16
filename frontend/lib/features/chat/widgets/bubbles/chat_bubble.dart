import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../shared/models/chat_message.dart';
import '../../helpers/mention_spans.dart';
import 'avatar.dart';
import 'bubble_footer.dart';
import 'bubble_header_decorations.dart';
import 'chat_bubble_menu.dart';
import 'hover_reaction_bar.dart';

/// 말풍선 모서리. 전부 20으로 둥글게, 그룹 첫 말풍선만 꼬리 쪽 위 모서리를 6으로
/// 눌러 카카오톡 꼬리처럼 보이게 한다(내 것은 오른쪽 위, 상대는 왼쪽 위).
BorderRadius bubbleRadius({required bool isMine, required bool isFirstInGroup}) {
  const round = Radius.circular(20);
  const tail = Radius.circular(6);
  return BorderRadius.only(
    topLeft: !isMine && isFirstInGroup ? tail : round,
    topRight: isMine && isFirstInGroup ? tail : round,
    bottomLeft: round,
    bottomRight: round,
  );
}

// ─────────────────────────────────────────────────────────────────
// Chat bubble
// ─────────────────────────────────────────────────────────────────
class ChatBubble extends StatefulWidget {
  final ChatMessage msg;
  final bool isMine;

  /// 로그인한 사용자명. @멘션이 나를 가리키는지 판정하는 데만 쓴다.
  /// 리스트가 이미 [isMine] 계산에 쓰는 값을 그대로 내려받는다 — 버블이 직접
  /// authProvider를 watch하면 화면에 뜬 모든 버블이 AuthState 전체를 구독한다.
  final String? me;

  final String time;
  final int readCount;
  final VoidCallback? onReply;
  final VoidCallback? onScrollToParent;
  final VoidCallback? onDelete;
  final VoidCallback? onEdit;
  final VoidCallback? onViewEditHistory;
  final VoidCallback? onReadCountTap;
  final void Function(String emoji)? onReaction;
  final VoidCallback? onForward;
  final VoidCallback? onPin;
  final VoidCallback? onRetry;
  final VoidCallback? onBookmark;
  final bool isBookmarked;
  final bool showAvatar;
  final bool showTime;
  final void Function(ChatMessage parent)? onOpenThread;
  final int replyCount;

  const ChatBubble({
    super.key,
    required this.msg,
    required this.isMine,
    required this.time,
    this.me,
    this.readCount = 0,
    this.onReply,
    this.onScrollToParent,
    this.onDelete,
    this.onEdit,
    this.onViewEditHistory,
    this.onReadCountTap,
    this.onReaction,
    this.onForward,
    this.onPin,
    this.onRetry,
    this.onBookmark,
    this.isBookmarked = false,
    this.showAvatar = true,
    this.showTime = true,
    this.onOpenThread,
    this.replyCount = 0,
  });

  @override
  State<ChatBubble> createState() => ChatBubbleState();
}

class ChatBubbleState extends State<ChatBubble> {
  bool _hovered = false;
  Timer? _hideTimer;

  static const _quickReactions = ['👍', '❤️', '😂', '😮', '😢', '✅'];

  @override
  void dispose() {
    _hideTimer?.cancel();
    super.dispose();
  }

  /// Debounced hover state for the floating action toolbar. The toolbar sits
  /// above the bubble (outside the parent MouseRegion's bounds), so moving the
  /// pointer onto it briefly leaves the bubble region. Delaying the hide lets
  /// the pointer cross the gap and reach the toolbar's own MouseRegion, which
  /// re-asserts hover — so the bar no longer vanishes before it can be used.
  void _setHovered(bool hovered) {
    _hideTimer?.cancel();
    if (hovered) {
      if (!_hovered) setState(() => _hovered = true);
    } else {
      // 800ms — 대각선 이동/살짝 벗어남에도 툴바가 살아있도록 넉넉하게.
      // (250ms는 버블→툴바로 마우스를 옮기는 실제 동선에서 부족했음)
      _hideTimer = Timer(const Duration(milliseconds: 800), () {
        if (mounted && _hovered) setState(() => _hovered = false);
      });
    }
  }

  /// 메시지 content의 @username 패턴을 하이라이트해 RichText로 반환.
  /// onMyBubble=true면 노란 내 말풍선 위에 맞춘 색을 쓴다.
  ///
  /// 본인이 호명됐는지는 [findMentionSpans]가 내 사용자명과 직접 대조해 판정한다.
  /// 예전에는 `@([A-Za-z0-9_.가-힣]{1,30})`로 이름 모양을 추측했는데, 가입 시
  /// 사용자명 검증이 없어서 공백·하이픈·악센트·30자 초과 이름이 잘려 나갔다.
  Widget _buildContentRichText(BuildContext context, String content, TextStyle baseStyle,
      {required bool onMyBubble}) {
    // 전달된 글은 남이 쓴 문장을 "[전달] <보낸이>: <원문>" 으로 다시 조립한 것이라
    // 서버가 멘션 행을 만들지 않는다(MentionTargets.shouldResolveMentions).
    // 여기서 하이라이트하면 오지 않을 알림을 약속하는 셈이다.
    //
    // 공백만 있는 값은 "전달 아님"으로 본다 — 서버(MentionTargets)와 전달 배지
    // (bubble_header_decorations)가 쓰는 판정과 같아야 셋이 따로 놀지 않는다.
    if (widget.msg.forwardedFrom?.trim().isNotEmpty ?? false) {
      return Text(content, style: baseStyle);
    }
    final cs = Theme.of(context).colorScheme;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final me = widget.me;
    final mentions =
        findMentionSpans(content, me: (me == null || me.isEmpty) ? null : me);
    if (mentions.isEmpty) {
      return Text(content, style: baseStyle);
    }
    final spans = <TextSpan>[];
    int cursor = 0;
    for (final m in mentions) {
      if (m.start > cursor) {
        spans.add(TextSpan(text: content.substring(cursor, m.start), style: baseStyle));
      }
      // 나를 부른 멘션은 버터 노랑 형광펜, 남을 부른 멘션은 파랑 글자.
      final fg = m.isMe
          ? (onMyBubble ? AppColors.mentionMe : AppColors.ink)
          : (onMyBubble || !isDark ? AppColors.blueDeep : cs.primary);
      final bg = m.isMe
          ? (onMyBubble ? Colors.white.withAlpha(170) : AppColors.myBubble)
          : (onMyBubble ? Colors.white.withAlpha(130) : cs.primary.withAlpha(28));
      spans.add(TextSpan(
        text: content.substring(m.start, m.end),
        style: baseStyle.copyWith(
          color: fg,
          fontWeight: FontWeight.w700,
          backgroundColor: bg,
        ),
      ));
      cursor = m.end;
    }
    if (cursor < content.length) {
      spans.add(TextSpan(text: content.substring(cursor), style: baseStyle));
    }
    return Text.rich(TextSpan(children: spans));
  }

  void _showDeleteSheet(BuildContext context) =>
      ChatBubbleMenu.showDeleteSheet(
        context,
        msg: widget.msg,
        isMine: widget.isMine,
        isBookmarked: widget.isBookmarked,
        onReply: widget.onReply,
        onForward: widget.onForward,
        onPin: widget.onPin,
        onBookmark: widget.onBookmark,
        onEdit: widget.onEdit,
        onViewEditHistory: widget.onViewEditHistory,
        onDelete: widget.onDelete,
        onReaction: widget.onReaction,
      );

  void _showContextMenu(BuildContext context, Offset position) =>
      ChatBubbleMenu.showContextMenu(
        context,
        position,
        msg: widget.msg,
        isMine: widget.isMine,
        isBookmarked: widget.isBookmarked,
        onReply: widget.onReply,
        onForward: widget.onForward,
        onPin: widget.onPin,
        onBookmark: widget.onBookmark,
        onEdit: widget.onEdit,
        onViewEditHistory: widget.onViewEditHistory,
        onDelete: widget.onDelete,
        onReaction: widget.onReaction,
      );

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final cp = ChatPalette.of(context);
    // Responsive max width: 70% of screen on mobile, capped at 320 on desktop
    final screenWidth = MediaQuery.of(context).size.width;
    final bubbleMaxWidth = screenWidth < 600
        ? (screenWidth * 0.70).clamp(180.0, 320.0)
        : 320.0;
    final radius = bubbleRadius(isMine: widget.isMine, isFirstInGroup: widget.showAvatar);
    final metaStyle = TextStyle(fontSize: 10, color: cp.meta);

    // Deleted message — compact tombstone bubble
    if (widget.msg.deleted) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          mainAxisAlignment: widget.isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
          children: [
            if (!widget.isMine) ...[
              // 일반 말풍선과 같은 규칙: 그룹 첫 줄만 아바타(와 꼬리), 나머지는 자리만.
              if (widget.showAvatar)
                Avatar(name: widget.msg.username, color: avatarColor(widget.msg.username))
              else
                const SizedBox(width: Avatar.size),
              const SizedBox(width: 8),
            ],
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: cp.otherBubble.withAlpha(170),
                borderRadius: radius,
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.not_interested, size: 13, color: cp.meta),
                  const SizedBox(width: 6),
                  Text(
                    '삭제된 메시지입니다.',
                    style: TextStyle(
                      fontSize: 13,
                      fontStyle: FontStyle.italic,
                      color: cp.meta,
                    ),
                  ),
                ],
              ),
            ),
            if (widget.isMine) const SizedBox(width: 6),
          ],
        ),
      );
    }

    final canReport = !widget.isMine && !widget.msg.deleted;
    final hasActions = canReport || widget.onDelete != null || widget.onEdit != null || widget.onViewEditHistory != null || widget.onReply != null || widget.onReaction != null || widget.onForward != null || widget.onPin != null || widget.onBookmark != null;

    final mine = widget.isMine;
    final bubbleColor = mine ? cp.myBubble : cp.otherBubble;
    final onBubble = mine ? cp.onMyBubble : cp.onOtherBubble;

    final bubbleBody = Container(
      constraints: BoxConstraints(maxWidth: bubbleMaxWidth),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: bubbleColor,
        borderRadius: radius,
        boxShadow: [
          BoxShadow(
            color: cp.bubbleShadow,
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (widget.msg.forwardedFrom != null)
            Container(
              margin: const EdgeInsets.only(bottom: 4),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: onBubble.withAlpha(16),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.forward, size: 12, color: onBubble.withAlpha(150)),
                  const SizedBox(width: 4),
                  Flexible(child: Text(
                    widget.msg.forwardedFrom!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11, color: onBubble.withAlpha(150)),
                  )),
                ],
              ),
            ),
          if (widget.msg.isReply) ...[
            GestureDetector(
              onTap: widget.onScrollToParent,
              child: Container(
                margin: const EdgeInsets.only(bottom: 6),
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: mine ? Colors.white.withAlpha(120) : cs.primary.withAlpha(20),
                  borderRadius: BorderRadius.circular(12),
                  border: Border(
                    left: BorderSide(
                        color: mine ? onBubble.withAlpha(90) : cs.primary, width: 3),
                  ),
                ),
                child: Text(
                  widget.msg.parentMessagePreview ?? '원본 메시지',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    fontStyle: FontStyle.italic,
                    color: onBubble.withAlpha(170),
                  ),
                ),
              ),
            ),
          ],
          _buildContentRichText(
            context,
            widget.msg.content,
            TextStyle(color: onBubble, fontSize: 14, height: 1.4),
            onMyBubble: mine,
          ),
        ],
      ),
    );

    final bubble = GestureDetector(
      onLongPress: hasActions ? () => _showDeleteSheet(context) : null,
      onSecondaryTapUp: hasActions
          ? (details) => _showContextMenu(context, details.globalPosition)
          : null,
      child: Padding(
      padding: EdgeInsets.only(top: widget.showAvatar ? 4 : 1, bottom: widget.showAvatar ? 2 : 1),
      child: Row(
        mainAxisAlignment:
            widget.isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        // 카카오톡처럼 아바타는 그룹 첫 줄의 위쪽(이름 옆)에 붙는다.
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!widget.isMine) ...[
            if (widget.showAvatar)
              Avatar(name: widget.msg.username, color: avatarColor(widget.msg.username))
            else
              const SizedBox(width: Avatar.size), // placeholder for alignment
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Column(
              crossAxisAlignment:
                  widget.isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              children: [
                BubbleHeaderDecorations(
                  msg: widget.msg,
                  isMine: widget.isMine,
                  showAvatar: widget.showAvatar,
                ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (widget.isMine)
                      Padding(
                        padding: const EdgeInsets.only(right: 6, bottom: 2),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (widget.readCount > 0)
                              GestureDetector(
                                onTap: widget.onReadCountTap,
                                child: Text(
                                  '읽음 ${widget.readCount}',
                                  style: TextStyle(
                                    fontSize: 10,
                                    color: cp.readCount,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ),
                            if (widget.msg.edited && widget.showTime)
                              Text('수정됨', style: metaStyle),
                            if (widget.showTime)
                              Text(widget.time, style: metaStyle),
                            if (widget.msg.deliveryStatus == MessageDeliveryStatus.sending)
                              Padding(
                                padding: const EdgeInsets.only(top: 2),
                                child: Icon(Icons.schedule, size: 11, color: cp.meta),
                              ),
                            if (widget.msg.deliveryStatus == MessageDeliveryStatus.failed)
                              GestureDetector(
                                onTap: widget.onRetry,
                                child: Padding(
                                  padding: const EdgeInsets.only(top: 2),
                                  child: Tooltip(
                                    message: '재전송',
                                    child: Icon(Icons.refresh, size: 13, color: cs.error),
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    Flexible(child: bubbleBody),
                    if (!widget.isMine && widget.showTime)
                      Padding(
                        padding: const EdgeInsets.only(left: 6, bottom: 2),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (widget.msg.edited)
                              Text('수정됨', style: metaStyle),
                            Text(widget.time, style: metaStyle),
                          ],
                        ),
                      ),
                  ],
                ),
                BubbleFooter(
                  msg: widget.msg,
                  isMine: widget.isMine,
                  replyCount: widget.replyCount,
                  onOpenThread: widget.onOpenThread,
                  onReaction: widget.onReaction,
                  onReply: widget.onReply,
                ),
              ],
            ),
          ),
          if (widget.isMine) const SizedBox(width: 6),
        ],
      ),
    ),  // Padding
    ); // GestureDetector

    // Swipe gestures: right = reply, left = action menu
    Widget result = bubble;

    if (!widget.msg.deleted && (widget.onReply != null || hasActions)) {
      result = Dismissible(
        key: ValueKey('swipe-${widget.msg.effectiveId}'),
        direction: widget.onReply != null && hasActions
            ? DismissDirection.horizontal
            : widget.onReply != null
                ? DismissDirection.startToEnd
                : DismissDirection.endToStart,
        confirmDismiss: (direction) async {
          if (direction == DismissDirection.startToEnd) {
            widget.onReply?.call();
          } else if (direction == DismissDirection.endToStart) {
            _showDeleteSheet(context);
          }
          return false;
        },
        background: Align(
          alignment: Alignment.centerLeft,
          child: Padding(
            padding: const EdgeInsets.only(left: 16),
            child: Icon(Icons.reply, color: Theme.of(context).colorScheme.primary.withAlpha(150)),
          ),
        ),
        secondaryBackground: Align(
          alignment: Alignment.centerRight,
          child: Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Icon(Icons.more_horiz, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(150)),
          ),
        ),
        child: result,
      );
    }

    // Desktop/web hover action toolbar: quick reactions + reply + more (⋯).
    // Surfaces the actions otherwise hidden behind long-press/right-click.
    final showHoverBar =
        kIsWeb && !widget.msg.deleted && (widget.onReaction != null || hasActions);
    if (!showHoverBar) return result;

    return MouseRegion(
      onEnter: (_) => _setHovered(true),
      onExit: (_) => _setHovered(false),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          result,
          if (_hovered)
            Positioned(
              top: -34,
              right: widget.isMine ? 0 : null,
              left: widget.isMine ? null : 0,
              // The bar sits outside the parent MouseRegion's bounds, so give it
              // its own region to keep hover alive while the pointer is on it.
              // The bottom padding is a transparent "bridge" whose hit area
              // reaches down to the bubble, so crossing the gap never drops hover.
              child: MouseRegion(
                onEnter: (_) => _setHovered(true),
                onExit: (_) => _setHovered(false),
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: HoverReactionBar(
                    reactions: _quickReactions,
                    onReaction: widget.onReaction,
                    onReply: widget.onReply,
                    onMore: hasActions
                        ? (pos) => _showContextMenu(context, pos)
                        : null,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
