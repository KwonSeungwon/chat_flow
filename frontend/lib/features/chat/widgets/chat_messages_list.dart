import 'dart:async';
import 'package:flutter/material.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/utils/time_format.dart';
import '../../../shared/models/chat_message.dart';
import 'bubbles/chat_bubble.dart';
import 'bubbles/file_bubble.dart';
import 'bubbles/system_bubble.dart';
import 'dividers/date_divider.dart';
import 'dividers/unread_divider.dart';

class ChatMessagesList extends StatefulWidget {
  final List<ChatMessage> messages;
  final String currentUsername;
  /// messageId → read count mapping (for read receipt display)
  final Map<String, int> readCounts;
  /// If set, auto-scroll to this message after build
  final String? scrollToMessageId;
  /// If set, briefly highlight this message (mention or notification jump)
  final String? highlightMessageId;
  final bool isLoadingHistory;
  final bool hasMoreHistory;
  final void Function()? onLoadMoreHistory;
  final void Function(ChatMessage)? onReplySelected;
  final void Function(String parentMessageId)? onScrollToParentMessage;
  final void Function(String messageId)? onDeleteMessage;
  final void Function(String messageId, String currentContent)? onEditMessage;
  final void Function(String messageId, String currentContent)? onViewEditHistory;
  final void Function(String messageId)? onReadCountTap;
  final void Function(String messageId, String emoji)? onReaction;
  final void Function(ChatMessage msg)? onForward;
  final void Function(String messageId)? onPin;
  final void Function(ChatMessage msg)? onRetry;
  final void Function(ChatMessage msg)? onBookmarkToggle;
  final Set<String> bookmarkedMessageIds;
  final String? lastReadMessageId;
  final void Function(ChatMessage parent)? onOpenThread;
  final int Function(String parentMessageId)? replyCountFor;

  const ChatMessagesList({
    super.key,
    required this.messages,
    required this.currentUsername,
    this.readCounts = const {},
    this.isLoadingHistory = false,
    this.hasMoreHistory = true,
    this.onLoadMoreHistory,
    this.scrollToMessageId,
    this.highlightMessageId,
    this.onReplySelected,
    this.onScrollToParentMessage,
    this.onDeleteMessage,
    this.onEditMessage,
    this.onViewEditHistory,
    this.onReadCountTap,
    this.onReaction,
    this.onForward,
    this.onPin,
    this.onRetry,
    this.onBookmarkToggle,
    this.bookmarkedMessageIds = const {},
    this.lastReadMessageId,
    this.onOpenThread,
    this.replyCountFor,
  });

  @override
  State<ChatMessagesList> createState() => _ChatMessagesListState();
}

/// Returns true when [newMessages] grew by prepending older history at the
/// front (the head changed), as opposed to appending new messages at the end.
/// Exposed for unit testing.
///
/// Edge case (benign): if the live buffer overflows its cap and the oldest
/// messages are trimmed off the front on the same update that appends a new
/// message, the head changes and this reports a prepend — causing a single
/// harmless one-frame anchor jump. Not worth guarding for a rare 500+ buffer.
@visibleForTesting
bool isPrependedHistory(
    List<ChatMessage> oldMessages, List<ChatMessage> newMessages) {
  return oldMessages.isNotEmpty &&
      newMessages.isNotEmpty &&
      newMessages.first.effectiveId != oldMessages.first.effectiveId;
}

class _ChatMessagesListState extends State<ChatMessagesList> {
  final _scrollController = ScrollController();
  final _targetKey = GlobalKey();
  final _unreadDividerKey = GlobalKey();
  bool _autoScroll = true;
  int _unreadCount = 0;
  String? _highlightedMessageId;
  Timer? _highlightTimer;
  String? _lastScrollTarget;
  List<Object>? _cachedItems;
  List<ChatMessage>? _lastMessages;
  String? _lastReadMessageIdCache;
  bool _initialUnreadJumpDone = false;

  /// Number of automatic loadMoreHistory attempts for search-jump target
  int _autoLoadAttempts = 0;
  static const int _maxAutoLoadAttempts = 3;

  /// True for one frame after a history prepend completes, used to suppress
  /// the scroll-listener's offset<100 load-more re-trigger.
  bool _suppressLoadMore = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(() {
      if (!_scrollController.hasClients) return;
      final offset = _scrollController.offset;
      final maxExtent = _scrollController.position.maxScrollExtent;
      final atBottom = offset >= maxExtent - 80;
      if (atBottom && !_autoScroll) {
        setState(() {
          _autoScroll = true;
          _unreadCount = 0;
        });
      } else if (!atBottom && _autoScroll) {
        _autoScroll = false;
      }
      // Trigger history load when near the top
      if (offset < 100 &&
          widget.hasMoreHistory &&
          !widget.isLoadingHistory &&
          !_suppressLoadMore) {
        widget.onLoadMoreHistory?.call();
      }
    });
    // If messages are already loaded when widget mounts (e.g. provider still alive),
    // attempt scroll immediately on the first frame.
    if (widget.scrollToMessageId != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _tryScrollToTarget();
      });
    }
  }

  /// Attempt to scroll to [widget.scrollToMessageId] if it exists in the list.
  /// When the target message is not in the current messages (e.g. older than
  /// the latest 50), automatically trigger [onLoadMoreHistory] up to
  /// [_maxAutoLoadAttempts] times (~150 messages) so the user does not have
  /// to scroll manually.
  void _tryScrollToTarget() {
    final target = widget.scrollToMessageId;
    if (target == null || target == _lastScrollTarget) return;
    final hasTarget = widget.messages.any((m) => m.effectiveId == target);
    if (hasTarget) {
      _autoLoadAttempts = 0;
      _lastScrollTarget = target;
      _autoScroll = false;
      _scheduleScrollToTarget();
      return;
    }
    // Target not found in loaded messages — load older history automatically.
    if (_autoLoadAttempts < _maxAutoLoadAttempts && widget.hasMoreHistory) {
      if (widget.isLoadingHistory) return; // wait for current load to finish
      _autoLoadAttempts++;
      _autoScroll = false; // suppress scroll-to-bottom during auto-load
      debugPrint(
        '[ChatMessagesList] target $target not found, '
        'loading more history (attempt $_autoLoadAttempts)',
      );
      widget.onLoadMoreHistory?.call();
      // didUpdateWidget will detect messages.length change and re-invoke
      // _tryScrollToTarget on the next frame.
      return;
    }
    // Max attempts reached or no more history — give up and notify user.
    if (_lastScrollTarget != target) {
      _lastScrollTarget = target; // prevent repeated SnackBars
      _autoLoadAttempts = 0;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('해당 메시지를 찾을 수 없습니다.'),
            ),
          );
        }
      });
    }
  }

  void _scheduleScrollToTarget() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final ctx = _targetKey.currentContext;
      if (ctx != null) {
        Scrollable.ensureVisible(
          ctx,
          alignment: 0.3,
          duration: const Duration(milliseconds: 400),
          curve: Curves.easeOutCubic,
        );
      }
      // Flash highlight for search-navigated messages
      if (widget.highlightMessageId != null) {
        setState(() => _highlightedMessageId = widget.highlightMessageId);
        _highlightTimer?.cancel();
        _highlightTimer = Timer(const Duration(seconds: 2), () {
          if (mounted) setState(() => _highlightedMessageId = null);
        });
      }
    });
  }

  /// Check whether the cached items contain the unread divider marker.
  bool _hasUnreadDivider() {
    return (_cachedItems ?? const []).any((item) => item == _unreadDividerMarker);
  }

  /// Scroll to the unread divider position so users start reading from the
  /// first unread message without manually scrolling up.
  void _scrollToUnreadDivider() {
    final ctx = _unreadDividerKey.currentContext;
    if (ctx != null) {
      Scrollable.ensureVisible(
        ctx,
        alignment: 0.2, // slightly below the top of the viewport
        duration: const Duration(milliseconds: 400),
        curve: Curves.easeOutCubic,
      );
    }
  }

  @override
  void didUpdateWidget(covariant ChatMessagesList oldWidget) {
    super.didUpdateWidget(oldWidget);

    // Room switch detection — messages cleared then reloaded
    if (oldWidget.messages.isNotEmpty && widget.messages.isEmpty) {
      _initialUnreadJumpDone = false;
      _autoLoadAttempts = 0;
      _lastScrollTarget = null;
    }

    // When messages load and there's a scroll target, scroll to it once.
    // Also retry when isLoadingHistory transitions false (load finished).
    if (widget.scrollToMessageId != null &&
        (widget.messages.length != oldWidget.messages.length ||
         (!widget.isLoadingHistory && oldWidget.isLoadingHistory))) {
      _tryScrollToTarget();
      if (_lastScrollTarget == widget.scrollToMessageId) return;
    }

    // Auto-scroll to unread divider on room entry (one-time)
    if (!_initialUnreadJumpDone &&
        widget.scrollToMessageId == null &&
        widget.messages.isNotEmpty &&
        widget.lastReadMessageId != null &&
        widget.lastReadMessageId!.isNotEmpty) {
      // Rebuild cached items so _hasUnreadDivider reflects latest state
      if (!identical(_lastMessages, widget.messages) ||
          _lastReadMessageIdCache != widget.lastReadMessageId) {
        _cachedItems = _buildItemsWithDividers(widget.messages);
        _lastMessages = widget.messages;
        _lastReadMessageIdCache = widget.lastReadMessageId;
      }
      if (_hasUnreadDivider()) {
        _initialUnreadJumpDone = true;
        _autoScroll = false;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _scrollToUnreadDivider();
        });
      } else {
        // lastReadMessageId is the last message or divider not applicable — no unread
        _initialUnreadJumpDone = true;
      }
    }

    if (widget.messages.length > oldWidget.messages.length) {
      // Distinguish prepend (older history loaded at top) from append (new
      // live messages at bottom).  A prepend changes the head of the list;
      // an append leaves it unchanged.
      final isPrepend =
          isPrependedHistory(oldWidget.messages, widget.messages);

      if (isPrepend) {
        // Anchor viewport: after the new items are laid out, jump the scroll
        // offset by the amount of content inserted above so the previously-
        // visible messages stay in place visually.
        if (_scrollController.hasClients) {
          final oldMaxExtent =
              _scrollController.position.maxScrollExtent;
          final oldOffset = _scrollController.offset;

          // Suppress the scroll-listener's load-more trigger for one frame
          // so the still-near-top offset doesn't immediately re-fire.
          _suppressLoadMore = true;

          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted || !_scrollController.hasClients) {
              _suppressLoadMore = false;
              return;
            }
            final newMaxExtent =
                _scrollController.position.maxScrollExtent;
            final delta = newMaxExtent - oldMaxExtent;
            if (delta > 0) {
              _scrollController.jumpTo(oldOffset + delta);
            }
            _suppressLoadMore = false;
          });
        }
        // Do NOT scroll to bottom and do NOT increment unread count —
        // these are old messages, not new ones.
      } else {
        // Append: new live messages arrived at the bottom.
        if (_autoScroll) {
          WidgetsBinding.instance
              .addPostFrameCallback((_) => _scrollToBottom());
        } else {
          setState(() => _unreadCount +=
              widget.messages.length - oldWidget.messages.length);
        }
      }
    }
  }

  void _scrollToBottom() {
    if (_scrollController.hasClients) {
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOutCubic,
      );
      setState(() => _unreadCount = 0);
    }
  }

  String _formatTime(String timestamp) {
    try {
      return hhmm(DateTime.parse(timestamp).toLocal());
    } catch (_) {
      return '';
    }
  }

  String _formatDate(String timestamp) {
    try {
      final dt = DateTime.parse(timestamp).toLocal();
      final now = DateTime.now();
      if (dt.year == now.year && dt.month == now.month && dt.day == now.day) {
        return '오늘';
      }
      final yesterday = now.subtract(const Duration(days: 1));
      if (dt.year == yesterday.year && dt.month == yesterday.month && dt.day == yesterday.day) {
        return '어제';
      }
      return '${dt.year}년 ${dt.month}월 ${dt.day}일';
    } catch (_) {
      return '';
    }
  }

  static const _unreadDividerMarker = '__UNREAD_DIVIDER__';

  List<Object> _buildItemsWithDividers(List<ChatMessage> messages) {
    final items = <Object>[];
    String? lastDate;
    bool unreadInserted = false;
    for (final msg in messages) {
      final date = _formatDate(msg.timestamp);
      if (date.isNotEmpty && date != lastDate) {
        items.add(date);
        lastDate = date;
      }
      items.add(msg);
      // Insert unread divider right after the last-read message
      if (!unreadInserted &&
          widget.lastReadMessageId != null &&
          widget.lastReadMessageId!.isNotEmpty &&
          msg.effectiveId == widget.lastReadMessageId &&
          msg != messages.last) {
        items.add(_unreadDividerMarker);
        unreadInserted = true;
      }
    }
    return items;
  }

  @override
  void dispose() {
    _highlightTimer?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.messages.isEmpty) {
      final colorScheme = Theme.of(context).colorScheme;
      final cp = ChatPalette.of(context);
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 72,
              height: 72,
              decoration: ShapeDecoration(
                shape: ContinuousRectangleBorder(
                    borderRadius: BorderRadius.circular(56)),
                color: cp.otherBubble,
                shadows: [
                  BoxShadow(
                    color: cp.bubbleShadow,
                    blurRadius: 16,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: Icon(Icons.chat_bubble_rounded,
                  size: 32, color: colorScheme.primary.withAlpha(170)),
            ),
            const SizedBox(height: 16),
            Text(
              '아직 메시지가 없습니다',
              style: TextStyle(
                  color: colorScheme.onSurface,
                  fontWeight: FontWeight.w600,
                  fontSize: 15),
            ),
            const SizedBox(height: 4),
            Text(
              '첫 메시지를 보내보세요!',
              style: TextStyle(color: cp.meta, fontSize: 13),
            ),
          ],
        ),
      );
    }

    if (!identical(_lastMessages, widget.messages) ||
        _lastReadMessageIdCache != widget.lastReadMessageId) {
      _cachedItems = _buildItemsWithDividers(widget.messages);
      _lastMessages = widget.messages;
      _lastReadMessageIdCache = widget.lastReadMessageId;
    }
    final items = _cachedItems!;
    final hasTopLoader = widget.isLoadingHistory && widget.messages.isNotEmpty;
    final topOffset = hasTopLoader ? 1 : 0;
    final totalCount = items.length + topOffset;

    return Stack(
      children: [
        ListView.builder(
          controller: _scrollController,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          itemCount: totalCount,
          itemBuilder: (context, index) {
            // History loading indicator at the top
            if (hasTopLoader && index == 0) {
              return const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Center(child: SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2))),
              );
            }
            final adjustedIndex = index - topOffset;
            final item = items[adjustedIndex];

            // Date divider or unread divider
            if (item is String) {
              if (item == _unreadDividerMarker) {
                return KeyedSubtree(
                  key: _unreadDividerKey,
                  child: const UnreadDivider(),
                );
              }
              return DateDivider(date: item);
            }

            final msg = item as ChatMessage;
            final type = msg.type.toUpperCase();
            final isTarget = msg.effectiveId == widget.scrollToMessageId;
            final isHighlighted = msg.effectiveId == _highlightedMessageId;

            // Grouping: check prev/next items for same user + same minute
            ChatMessage? prevMsg;
            ChatMessage? nextMsg;
            for (int p = adjustedIndex - 1; p >= 0; p--) {
              if (items[p] is ChatMessage) { prevMsg = items[p] as ChatMessage; break; }
            }
            for (int n = adjustedIndex + 1; n < items.length; n++) {
              if (items[n] is ChatMessage) { nextMsg = items[n] as ChatMessage; break; }
            }
            final bool isFirstInGroup = prevMsg == null ||
                prevMsg.username != msg.username ||
                prevMsg.type.toUpperCase() != type;
            final bool isLastInGroup = nextMsg == null ||
                nextMsg.username != msg.username ||
                nextMsg.type.toUpperCase() != type;
            // Show time only if last in group OR next message is in a different minute
            bool showTime = isLastInGroup;
            if (!showTime) {
              try {
                final t1 = DateTime.parse(msg.timestamp);
                final t2 = DateTime.parse(nextMsg.timestamp);
                showTime = t1.minute != t2.minute || t1.hour != t2.hour;
              } catch (_) { showTime = true; }
            }

            Widget bubble;
            if (type == 'JOIN' || type == 'LEAVE' || type == 'SYSTEM') {
              bubble = SystemBubble(msg: msg);
            } else if (msg.isFileMessage) {
              final isMine = msg.username == widget.currentUsername;
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              bubble = FileBubble(
                msg: msg,
                isMine: isMine,
                time: _formatTime(msg.timestamp),
                readCount: readCount,
                onOpenThread: widget.onOpenThread,
                replyCount: widget.replyCountFor?.call(msg.effectiveId) ?? 0,
              );
            } else {
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              final isMine = msg.username == widget.currentUsername;

              bubble = ChatBubble(
                msg: msg,
                isMine: isMine,
                me: widget.currentUsername,
                time: _formatTime(msg.timestamp),
                readCount: readCount,
                onReply: (!msg.deleted && widget.onReplySelected != null)
                    ? () => widget.onReplySelected!(msg)
                    : null,
                onScrollToParent: (msg.isReply && msg.parentMessageId != null && widget.onScrollToParentMessage != null)
                    ? () => widget.onScrollToParentMessage!(msg.parentMessageId!)
                    : null,
                onDelete: (isMine && !msg.deleted && widget.onDeleteMessage != null)
                    ? () => widget.onDeleteMessage!(msg.effectiveId)
                    : null,
                onEdit: (isMine && !msg.deleted && widget.onEditMessage != null)
                    ? () => widget.onEditMessage!(msg.effectiveId, msg.content)
                    : null,
                onViewEditHistory: (msg.edited && !msg.deleted && widget.onViewEditHistory != null)
                    ? () => widget.onViewEditHistory!(msg.effectiveId, msg.content)
                    : null,
                onReadCountTap: (isMine && readCount > 0 && widget.onReadCountTap != null)
                    ? () => widget.onReadCountTap!(msg.effectiveId)
                    : null,
                onReaction: (!msg.deleted && widget.onReaction != null)
                    ? (emoji) => widget.onReaction!(msg.effectiveId, emoji)
                    : null,
                onForward: (!msg.deleted && widget.onForward != null)
                    ? () => widget.onForward!(msg)
                    : null,
                onPin: (!msg.deleted && widget.onPin != null)
                    ? () => widget.onPin!(msg.effectiveId)
                    : null,
                onRetry: (isMine && msg.deliveryStatus == MessageDeliveryStatus.failed && widget.onRetry != null)
                    ? () => widget.onRetry!(msg)
                    : null,
                onBookmark: (!msg.deleted && widget.onBookmarkToggle != null)
                    ? () => widget.onBookmarkToggle!(msg)
                    : null,
                isBookmarked: widget.bookmarkedMessageIds.contains(msg.effectiveId),
                showAvatar: isFirstInGroup,
                showTime: showTime,
                onOpenThread: widget.onOpenThread,
                replyCount: widget.replyCountFor?.call(msg.effectiveId) ?? 0,
              );
            }

            // Wrap with highlight overlay for search-navigated messages
            if (isHighlighted) {
              bubble = AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                decoration: BoxDecoration(
                  // 내 말풍선이 노랑이라 하이라이트는 파랑 계열로 띄운다.
                  color: Theme.of(context).colorScheme.primary.withAlpha(isHighlighted ? 40 : 0),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: bubble,
              );
            }

            // Wrap with GlobalKey so we can scroll to this item
            if (isTarget) {
              bubble = KeyedSubtree(key: _targetKey, child: bubble);
            }
            // Stable identity per message, so inserts above (history
            // pages) don't hand one bubble's element state to another.
            return KeyedSubtree(key: ValueKey(msg.effectiveId), child: bubble);
          },
        ),

        // Unread badge
        if (_unreadCount > 0)
          Positioned(
            bottom: 12,
            left: 0,
            right: 0,
            child: Center(
              child: GestureDetector(
                onTap: _scrollToBottom,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 7),
                  decoration: BoxDecoration(
                    gradient: AppColors.accentGradient,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: AppColors.blue.withAlpha(90),
                        blurRadius: 14,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.arrow_downward_rounded,
                          size: 14, color: Colors.white),
                      const SizedBox(width: 6),
                      Text(
                        '새 메시지 ${_unreadCount > 99 ? '99+' : _unreadCount}개',
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
