import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../shared/models/chat_message.dart';
import 'avatar.dart';
import 'bubble_footer.dart';
import 'bubble_header_decorations.dart';
import 'chat_bubble_menu.dart';
import 'hover_reaction_bar.dart';

// ─────────────────────────────────────────────────────────────────
// Chat bubble
// ─────────────────────────────────────────────────────────────────
class ChatBubble extends StatefulWidget {
  final ChatMessage msg;
  final bool isMine;
  final String time;
  final bool isAiQuestion;
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
    this.isAiQuestion = false,
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
  /// invertColors=true면 어두운 배경 위(본인 버블)에 맞게 대비를 조정.
  Widget _buildContentRichText(BuildContext context, String content, TextStyle baseStyle,
      {required bool invertColors, String? highlightMe}) {
    final pattern = RegExp(r'@([A-Za-z0-9_\.가-힣]{1,30})');
    final matches = pattern.allMatches(content).toList();
    if (matches.isEmpty) {
      return Text(content, style: baseStyle);
    }
    final spans = <TextSpan>[];
    int cursor = 0;
    for (final m in matches) {
      if (m.start > cursor) {
        spans.add(TextSpan(text: content.substring(cursor, m.start), style: baseStyle));
      }
      final mentioned = m.group(1) ?? '';
      final isMeMentioned = highlightMe != null && mentioned == highlightMe;
      final fg = invertColors
          ? (isMeMentioned ? const Color(0xFFFFE082) : Colors.white)
          : (isMeMentioned ? const Color(0xFFB71C1C) : AppColors.primary);
      final bg = isMeMentioned
          ? (invertColors ? Colors.white.withAlpha(40) : const Color(0xFFFFF59D))
          : (invertColors ? Colors.white.withAlpha(30) : AppColors.primary.withAlpha(25));
      spans.add(TextSpan(
        text: m.group(0),
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
    // Responsive max width: 70% of screen on mobile, capped at 320 on desktop
    final screenWidth = MediaQuery.of(context).size.width;
    final bubbleMaxWidth = screenWidth < 600
        ? (screenWidth * 0.70).clamp(180.0, 320.0)
        : 320.0;
    final radius = BorderRadius.only(
      topLeft: const Radius.circular(20),
      topRight: const Radius.circular(20),
      bottomLeft: Radius.circular(widget.isMine ? 20 : 5),
      bottomRight: Radius.circular(widget.isMine ? 5 : 20),
    );

    // Deleted message — compact tombstone bubble
    if (widget.msg.deleted) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          mainAxisAlignment: widget.isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
          children: [
            if (!widget.isMine) ...[
              Avatar(name: widget.msg.username, color: avatarColor(widget.msg.username)),
              const SizedBox(width: 8),
            ],
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainer,
                borderRadius: radius,
                border: Border.all(color: Theme.of(context).colorScheme.outline.withAlpha(60)),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.not_interested, size: 13,
                      color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120)),
                  const SizedBox(width: 6),
                  Text(
                    '삭제된 메시지입니다.',
                    style: TextStyle(
                      fontSize: 13,
                      fontStyle: FontStyle.italic,
                      color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140),
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

    final isUrgent = widget.msg.priority.toUpperCase() == 'URGENT' || widget.msg.priority.toUpperCase() == 'STAT';

    final canReport = !widget.isMine && !widget.msg.deleted;
    final hasActions = canReport || widget.onDelete != null || widget.onEdit != null || widget.onViewEditHistory != null || widget.onReply != null || widget.onReaction != null || widget.onForward != null || widget.onPin != null || widget.onBookmark != null;

    final bubble = GestureDetector(
      onLongPress: hasActions ? () => _showDeleteSheet(context) : null,
      onSecondaryTapUp: hasActions
          ? (details) => _showContextMenu(context, details.globalPosition)
          : null,
      child: Padding(
      padding: EdgeInsets.only(top: widget.showAvatar ? 3 : 1, bottom: widget.showAvatar ? 3 : 1),
      child: Row(
        mainAxisAlignment:
            widget.isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!widget.isMine) ...[
            if (widget.showAvatar)
              Avatar(name: widget.msg.username, color: avatarColor(widget.msg.username))
            else
              const SizedBox(width: 32), // placeholder for alignment
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
                  isUrgent: isUrgent,
                ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (widget.isMine)
                      Padding(
                        padding: const EdgeInsets.only(right: 5, bottom: 3),
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
                                    color: Theme.of(context).colorScheme.primary.withAlpha(180),
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                            if (widget.msg.edited && widget.showTime)
                              Text(
                                '수정됨',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120),
                                ),
                              ),
                            if (widget.showTime)
                              Text(
                                widget.time,
                                style: TextStyle(
                                  fontSize: 10,
                                  color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140),
                                ),
                              ),
                            if (widget.msg.deliveryStatus == MessageDeliveryStatus.sending)
                              Padding(
                                padding: const EdgeInsets.only(top: 2),
                                child: Icon(Icons.schedule, size: 11, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120)),
                              ),
                            if (widget.msg.deliveryStatus == MessageDeliveryStatus.failed)
                              GestureDetector(
                                onTap: widget.onRetry,
                                child: const Padding(
                                  padding: EdgeInsets.only(top: 2),
                                  child: Tooltip(
                                    message: '재전송',
                                    child: Icon(Icons.refresh, size: 13, color: Colors.red),
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    Flexible(
                      child: widget.isMine
                          ? Container(
                              constraints:
                                  BoxConstraints(maxWidth: bubbleMaxWidth),
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 14, vertical: 10),
                              decoration: BoxDecoration(
                                gradient: widget.msg.priority == 'STAT'
                                    ? const LinearGradient(
                                        colors: [Color(0xFFE53935), Color(0xFFC62828)],
                                      )
                                    : widget.msg.priority == 'URGENT'
                                        ? const LinearGradient(
                                            colors: [Color(0xFFFB8C00), Color(0xFFE65100)],
                                          )
                                        : widget.isAiQuestion
                                            ? const LinearGradient(
                                                colors: [Color(0xFF7C3AED), Color(0xFF6D28D9)],
                                              )
                                            : AppColors.myBubbleGradient,
                                borderRadius: radius,
                                boxShadow: [
                                  BoxShadow(
                                    color: widget.msg.priority == 'STAT'
                                        ? const Color(0xFFD32F2F).withAlpha(90)
                                        : widget.msg.priority == 'URGENT'
                                            ? const Color(0xFFF57C00).withAlpha(70)
                                            : (widget.isAiQuestion ? const Color(0xFF7C3AED) : AppColors.primary).withAlpha(50),
                                    blurRadius: 10,
                                    offset: const Offset(0, 3),
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
                                        color: Colors.white.withAlpha(20),
                                        borderRadius: BorderRadius.circular(6),
                                      ),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Icon(Icons.forward, size: 12, color: Colors.white.withAlpha(160)),
                                          const SizedBox(width: 4),
                                          Flexible(child: Text(
                                            widget.msg.forwardedFrom!,
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: TextStyle(fontSize: 11, color: Colors.white.withAlpha(160)),
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
                                          color: Colors.white.withAlpha(25),
                                          borderRadius: BorderRadius.circular(8),
                                          border: Border(
                                            left: BorderSide(color: Colors.white.withAlpha(120), width: 3),
                                          ),
                                        ),
                                        child: Text(
                                          widget.msg.parentMessagePreview ?? '원본 메시지',
                                          maxLines: 2,
                                          overflow: TextOverflow.ellipsis,
                                          style: TextStyle(
                                            fontSize: 12,
                                            fontStyle: FontStyle.italic,
                                            color: Colors.white.withAlpha(180),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                  if (widget.isAiQuestion)
                                    Padding(
                                      padding: const EdgeInsets.only(bottom: 4),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Icon(Icons.auto_awesome, size: 12, color: Colors.white.withAlpha(200)),
                                          const SizedBox(width: 4),
                                          Text('To AI', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white.withAlpha(200))),
                                        ],
                                      ),
                                    ),
                                  _buildContentRichText(
                                    context,
                                    widget.isAiQuestion ? widget.msg.content.replaceFirst('[AI에게] ', '') : widget.msg.content,
                                    const TextStyle(color: Colors.white, fontSize: 14, height: 1.4),
                                    invertColors: true,
                                  ),
                                ],
                              ),
                            )
                          : Container(
                              constraints:
                                  BoxConstraints(maxWidth: bubbleMaxWidth),
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 14, vertical: 10),
                              decoration: BoxDecoration(
                                color: widget.msg.priority == 'STAT'
                                    ? const Color(0xFFD32F2F).withAlpha(18)
                                    : widget.msg.priority == 'URGENT'
                                        ? const Color(0xFFF57C00).withAlpha(18)
                                        : Theme.of(context).colorScheme.surfaceContainer,
                                borderRadius: radius,
                                border: Border.all(
                                    color: widget.msg.priority == 'STAT'
                                        ? const Color(0xFFD32F2F)
                                        : widget.msg.priority == 'URGENT'
                                            ? const Color(0xFFF57C00)
                                            : Theme.of(context).colorScheme.outline.withAlpha(80),
                                    width: (widget.msg.priority == 'STAT' || widget.msg.priority == 'URGENT') ? 1.5 : 1),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  if (widget.msg.forwardedFrom != null)
                                    Container(
                                      margin: const EdgeInsets.only(bottom: 4),
                                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                      decoration: BoxDecoration(
                                        color: Theme.of(context).colorScheme.onSurface.withAlpha(12),
                                        borderRadius: BorderRadius.circular(6),
                                      ),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Icon(Icons.forward, size: 12, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140)),
                                          const SizedBox(width: 4),
                                          Flexible(child: Text(
                                            widget.msg.forwardedFrom!,
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140)),
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
                                          color: Theme.of(context).colorScheme.primary.withAlpha(15),
                                          borderRadius: BorderRadius.circular(8),
                                          border: Border(
                                            left: BorderSide(color: AppColors.primary, width: 3),
                                          ),
                                        ),
                                        child: Text(
                                          widget.msg.parentMessagePreview ?? '원본 메시지',
                                          maxLines: 2,
                                          overflow: TextOverflow.ellipsis,
                                          style: TextStyle(
                                            fontSize: 12,
                                            fontStyle: FontStyle.italic,
                                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                  _buildContentRichText(
                                    context,
                                    widget.msg.content,
                                    TextStyle(
                                        color: Theme.of(context).colorScheme.onSurface,
                                        fontSize: 14,
                                        height: 1.4),
                                    invertColors: false,
                                  ),
                                ],
                              ),
                            ),
                    ),
                    if (!widget.isMine && widget.showTime)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, bottom: 3),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (widget.msg.edited)
                              Text(
                                '수정됨',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120),
                                ),
                              ),
                            Text(widget.time,
                                style: TextStyle(
                                    fontSize: 10, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140))),
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
