import 'dart:async';
import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:emoji_picker_flutter/emoji_picker_flutter.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/utils/url_helper.dart';
import '../../../shared/models/chat_message.dart';
import '../../../shared/models/patient_card.dart';
import '../admin/widgets/message_report_dialog.dart';
import 'patient_card_widget.dart';
import 'pdf_viewer_dialog.dart';

class ChatMessagesList extends StatefulWidget {
  final List<ChatMessage> messages;
  final String currentUsername;
  final bool isAiLoading;
  /// messageId → read count mapping (for read receipt display)
  final Map<String, int> readCounts;
  /// If set, auto-scroll to this message after build
  final String? scrollToMessageId;
  /// If set, briefly highlight this message (search result)
  final String? highlightMessageId;
  final bool isLoadingHistory;
  final bool hasMoreHistory;
  final void Function()? onLoadMoreHistory;
  final void Function(ChatMessage)? onReplySelected;
  final void Function(String parentMessageId)? onScrollToParentMessage;
  final void Function(String messageId)? onDeleteMessage;
  final void Function(String messageId, String currentContent)? onEditMessage;
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
    this.isAiLoading = false,
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
      if (offset < 100 && widget.hasMoreHistory && !widget.isLoadingHistory) {
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
      if (_autoScroll) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToBottom());
      } else {
        setState(() =>
            _unreadCount += widget.messages.length - oldWidget.messages.length);
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
      return DateFormat('HH:mm').format(DateTime.parse(timestamp).toLocal());
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
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: colorScheme.surfaceContainer,
                border: Border.all(color: colorScheme.outline),
              ),
              child: Icon(Icons.chat_bubble_outline_rounded,
                  size: 30, color: colorScheme.onSurfaceVariant.withAlpha(100)),
            ),
            const SizedBox(height: 16),
            Text(
              '아직 메시지가 없습니다',
              style: TextStyle(
                  color: colorScheme.onSurfaceVariant,
                  fontWeight: FontWeight.w500,
                  fontSize: 15),
            ),
            const SizedBox(height: 4),
            Text(
              '첫 메시지를 보내보세요!',
              style: TextStyle(color: colorScheme.onSurfaceVariant.withAlpha(150), fontSize: 13),
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
    final totalCount = items.length + topOffset + (widget.isAiLoading ? 1 : 0);

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
            // AI loading indicator at the bottom
            if (adjustedIndex == items.length && widget.isAiLoading) {
              return const _AiLoadingBubble();
            }
            final item = items[adjustedIndex];

            // Date divider or unread divider
            if (item is String) {
              if (item == _unreadDividerMarker) {
                return KeyedSubtree(
                  key: _unreadDividerKey,
                  child: _UnreadDivider(),
                );
              }
              return _DateDivider(date: item);
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
            if (!showTime && nextMsg != null) {
              try {
                final t1 = DateTime.parse(msg.timestamp);
                final t2 = DateTime.parse(nextMsg.timestamp);
                showTime = t1.minute != t2.minute || t1.hour != t2.hour;
              } catch (_) { showTime = true; }
            }

            Widget bubble;
            if (type == 'JOIN' || type == 'LEAVE' || type == 'SYSTEM') {
              bubble = _SystemBubble(msg: msg);
            } else if (type == 'AI_SUMMARY' || msg.isAiGenerated) {
              bubble = _AiSummaryCard(msg: msg);
            } else if (type == 'PATIENT_CARD') {
              final card = PatientCard.tryParseContent(msg.content);
              final isMine = msg.username == widget.currentUsername;
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              if (card != null) {
                bubble = _PatientCardBubble(
                  msg: msg,
                  card: card,
                  isMine: isMine,
                  time: _formatTime(msg.timestamp),
                  readCount: readCount,
                );
              } else {
                // Malformed JSON — fall back to plain text bubble so the
                // message is not silently hidden.
                bubble = _ChatBubble(
                  msg: msg,
                  isMine: isMine,
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
                  onReadCountTap: (isMine && readCount > 0 && widget.onReadCountTap != null)
                      ? () => widget.onReadCountTap!(msg.effectiveId)
                      : null,
                  onOpenThread: widget.onOpenThread,
                  replyCount: widget.replyCountFor?.call(msg.effectiveId) ?? 0,
                );
              }
            } else if (msg.isFileMessage) {
              final isMine = msg.username == widget.currentUsername;
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              bubble = _FileBubble(
                msg: msg,
                isMine: isMine,
                time: _formatTime(msg.timestamp),
                readCount: readCount,
              );
            } else {
              // SBAR structured message detection
              Map<String, dynamic>? sbarData;
              if (msg.content.startsWith('{') && msg.content.contains('"type":"SBAR"')) {
                try { sbarData = jsonDecode(msg.content) as Map<String, dynamic>; } catch (_) {}
              }

              final isAiQuestion = msg.content.startsWith('[AI에게] ');
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              final isMine = msg.username == widget.currentUsername;

              if (sbarData != null) {
                bubble = Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (!isMine) ...[
                        _Avatar(name: msg.username, color: AppColors.avatarPalette[msg.username.hashCode.abs() % AppColors.avatarPalette.length]),
                        const SizedBox(width: 8),
                      ],
                      Flexible(child: Column(
                        crossAxisAlignment: isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
                        children: [
                          if (!isMine && isFirstInGroup)
                            Padding(padding: const EdgeInsets.only(left: 4, bottom: 3), child: Text(msg.username, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Theme.of(context).colorScheme.onSurfaceVariant))),
                          _SbarCardWidget(sbar: sbarData),
                          if (showTime) Padding(padding: const EdgeInsets.only(top: 2), child: Text(_formatTime(msg.timestamp), style: TextStyle(fontSize: 10, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140)))),
                        ],
                      )),
                      if (isMine) const SizedBox(width: 6),
                    ],
                  ),
                );
              } else {
              bubble = _ChatBubble(
                msg: msg,
                isMine: isMine,
                time: _formatTime(msg.timestamp),
                isAiQuestion: isAiQuestion,
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
              } // end non-SBAR else
            }

            // Wrap with highlight overlay for search-navigated messages
            if (isHighlighted) {
              bubble = AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                decoration: BoxDecoration(
                  color: Colors.amber.withAlpha(isHighlighted ? 45 : 0),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: bubble,
              );
            }

            // Wrap with GlobalKey so we can scroll to this item
            if (isTarget) {
              return KeyedSubtree(key: _targetKey, child: bubble);
            }
            return bubble;
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
                    color: AppColors.primary,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: AppColors.primary.withAlpha(90),
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

// ─────────────────────────────────────────────────────────────────
// System message (JOIN / LEAVE / SYSTEM)
// ─────────────────────────────────────────────────────────────────
class _SystemBubble extends StatelessWidget {
  final ChatMessage msg;
  const _SystemBubble({required this.msg});

  String get _text {
    final type = msg.type.toUpperCase();
    if (type == 'JOIN') return '${msg.username}님이 입장했습니다';
    if (type == 'LEAVE') return '${msg.username}님이 퇴장했습니다';
    return msg.content;
  }

  IconData? get _alertIcon {
    if (msg.content.startsWith('[처방알림]')) return Icons.medication_rounded;
    if (msg.content.startsWith('[검사알림]')) return Icons.science_rounded;
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final icon = _alertIcon;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Center(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.85,
          ),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
            decoration: BoxDecoration(
              color: cs.surfaceContainer,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: cs.outline.withAlpha(80)),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (icon != null) ...[
                  Icon(icon, size: 13, color: cs.onSurfaceVariant.withAlpha(160)),
                  const SizedBox(width: 5),
                ],
                Flexible(
                  child: Text(
                    _text,
                    style: TextStyle(
                      fontSize: 12,
                      color: cs.onSurfaceVariant.withAlpha(160),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// AI bubble (chat bubble style, typewriter animation)
// ─────────────────────────────────────────────────────────────────
class _AiSummaryCard extends StatefulWidget {
  final ChatMessage msg;
  const _AiSummaryCard({required this.msg});

  @override
  State<_AiSummaryCard> createState() => _AiSummaryCardState();
}

class _AiSummaryCardState extends State<_AiSummaryCard> {
  int _visibleLength = 0;
  Timer? _timer;

  bool get _isRecent {
    try {
      final ts = DateTime.parse(widget.msg.timestamp).toUtc();
      return DateTime.now().toUtc().difference(ts).inSeconds < 60;
    } catch (_) {
      return false;
    }
  }

  @override
  void initState() {
    super.initState();
    if (_isRecent) {
      _startTyping();
    } else {
      _visibleLength = widget.msg.content.length;
    }
  }

  void _startTyping() {
    const charsPerTick = 1;
    _timer = Timer.periodic(const Duration(milliseconds: 30), (timer) {
      if (!mounted) { timer.cancel(); return; }
      if (_visibleLength < widget.msg.content.length) {
        setState(() {
          _visibleLength = (_visibleLength + charsPerTick)
              .clamp(0, widget.msg.content.length);
        });
      } else {
        _timer?.cancel();
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final displayText = widget.msg.content.substring(0, _visibleLength);
    final isTyping = _visibleLength < widget.msg.content.length;
    final radius = const BorderRadius.only(
      topLeft: Radius.circular(20),
      topRight: Radius.circular(20),
      bottomLeft: Radius.circular(5),
      bottomRight: Radius.circular(20),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          // AI avatar
          Container(
            width: 30,
            height: 30,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.secondary.withAlpha(40),
              border: Border.all(color: AppColors.secondary.withAlpha(120)),
            ),
            child: const Center(
              child: Text('AI', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.secondary)),
            ),
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(left: 4, bottom: 3),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        'ChatFlow AI',
                        style: const TextStyle(
                          fontSize: 11,
                          color: AppColors.secondary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (isTyping) ...[
                        const SizedBox(width: 8),
                        _TypingDots(),
                      ],
                    ],
                  ),
                ),
                Container(
                  constraints: BoxConstraints(
                    maxWidth: MediaQuery.of(context).size.width < 600
                        ? MediaQuery.of(context).size.width * 0.70
                        : 320,
                  ),
                  padding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 10),
                  decoration: BoxDecoration(
                    color: AppColors.secondary.withAlpha(18),
                    borderRadius: radius,
                    border: Border.all(
                        color: AppColors.secondary.withAlpha(80), width: 1),
                  ),
                  child: Text(
                    displayText,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 14,
                      height: 1.4,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _TypingDots extends StatefulWidget {
  @override
  State<_TypingDots> createState() => _TypingDotsState();
}

class _TypingDotsState extends State<_TypingDots>
    with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 900),
    )..repeat();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (_, __) {
        final phase = (_ctrl.value * 3).floor();
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: List.generate(3, (i) {
            return Container(
              margin: const EdgeInsets.symmetric(horizontal: 1.5),
              width: 4,
              height: 4,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppColors.secondary.withAlpha(i == phase ? 220 : 80),
              ),
            );
          }),
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// AI loading indicator bubble
// ─────────────────────────────────────────────────────────────────
class _AiLoadingBubble extends StatelessWidget {
  const _AiLoadingBubble();

  @override
  Widget build(BuildContext context) {
    final radius = const BorderRadius.only(
      topLeft: Radius.circular(20),
      topRight: Radius.circular(20),
      bottomLeft: Radius.circular(5),
      bottomRight: Radius.circular(20),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Container(
            width: 30,
            height: 30,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.secondary.withAlpha(40),
              border: Border.all(color: AppColors.secondary.withAlpha(120)),
            ),
            child: const Center(
              child: Text('AI', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.secondary)),
            ),
          ),
          const SizedBox(width: 8),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Padding(
                padding: EdgeInsets.only(left: 4, bottom: 3),
                child: Text(
                  'ChatFlow AI',
                  style: TextStyle(fontSize: 11, color: AppColors.secondary, fontWeight: FontWeight.w600),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
                decoration: BoxDecoration(
                  color: AppColors.secondary.withAlpha(18),
                  borderRadius: radius,
                  border: Border.all(color: AppColors.secondary.withAlpha(80), width: 1),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: AppColors.secondary,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Text(
                      '답변 생성 중...',
                      style: TextStyle(
                        color: AppColors.secondary.withAlpha(180),
                        fontSize: 13,
                        fontStyle: FontStyle.italic,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Chat bubble
// ─────────────────────────────────────────────────────────────────
class _ChatBubble extends StatefulWidget {
  final ChatMessage msg;
  final bool isMine;
  final String time;
  final bool isAiQuestion;
  final int readCount;
  final VoidCallback? onReply;
  final VoidCallback? onScrollToParent;
  final VoidCallback? onDelete;
  final VoidCallback? onEdit;
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

  const _ChatBubble({
    required this.msg,
    required this.isMine,
    required this.time,
    this.isAiQuestion = false,
    this.readCount = 0,
    this.onReply,
    this.onScrollToParent,
    this.onDelete,
    this.onEdit,
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
  State<_ChatBubble> createState() => _ChatBubbleState();
}

class _ChatBubbleState extends State<_ChatBubble> {
  bool _hovered = false;

  Color _avatarColor(String name) =>
      AppColors.avatarPalette[name.hashCode.abs() % AppColors.avatarPalette.length];

  static const _quickReactions = ['👍', '❤️', '😂', '😮', '😢', '✅'];

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
    return RichText(text: TextSpan(children: spans));
  }

  void _showDeleteSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            Container(
              width: 36, height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.withAlpha(80),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            if (widget.onReaction != null && !widget.msg.deleted)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    ..._quickReactions.map((e) => GestureDetector(
                      onTap: () { Navigator.of(context).pop(); widget.onReaction!(e); },
                      child: Text(e, style: const TextStyle(fontSize: 24)),
                    )),
                    GestureDetector(
                      onTap: () {
                        Navigator.of(context).pop();
                        _showEmojiPicker(context);
                      },
                      child: Container(
                        width: 36, height: 36,
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(18),
                        ),
                        child: const Icon(Icons.add, size: 20),
                      ),
                    ),
                  ],
                ),
              ),
            if (widget.onReply != null)
              ListTile(
                leading: const Icon(Icons.reply),
                title: const Text('답글'),
                onTap: () { Navigator.of(context).pop(); widget.onReply?.call(); },
              ),
            if (widget.onForward != null)
              ListTile(
                leading: const Icon(Icons.forward_outlined),
                title: const Text('전달'),
                onTap: () { Navigator.of(context).pop(); widget.onForward?.call(); },
              ),
            if (widget.onPin != null)
              ListTile(
                leading: Icon(widget.msg.pinned ? Icons.push_pin : Icons.push_pin_outlined),
                title: Text(widget.msg.pinned ? '고정 해제' : '메시지 고정'),
                onTap: () { Navigator.of(context).pop(); widget.onPin?.call(); },
              ),
            if (widget.onBookmark != null)
              ListTile(
                leading: Icon(widget.isBookmarked ? Icons.bookmark : Icons.bookmark_border),
                title: Text(widget.isBookmarked ? '북마크 해제' : '북마크'),
                onTap: () { Navigator.of(context).pop(); widget.onBookmark?.call(); },
              ),
            if (!widget.msg.deleted)
              ListTile(
                leading: const Icon(Icons.copy_outlined),
                title: const Text('복사'),
                onTap: () {
                  Navigator.of(context).pop();
                  Clipboard.setData(ClipboardData(text: widget.msg.content));
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('메시지가 복사되었습니다.'), duration: Duration(seconds: 1)));
                },
              ),
            if (widget.onEdit != null)
              ListTile(
                leading: const Icon(Icons.edit_outlined),
                title: const Text('메시지 수정'),
                onTap: () { Navigator.of(context).pop(); widget.onEdit?.call(); },
              ),
            if (widget.onDelete != null)
              ListTile(
                leading: const Icon(Icons.delete_outline, color: Colors.red),
                title: const Text('메시지 삭제', style: TextStyle(color: Colors.red)),
                onTap: () { Navigator.of(context).pop(); widget.onDelete?.call(); },
              ),
            // 자기 메시지 / 삭제된 메시지에는 신고 노출 안 함.
            if (!widget.isMine && !widget.msg.deleted)
              ListTile(
                leading: const Icon(Icons.flag_outlined, color: Colors.orange),
                title: const Text('신고', style: TextStyle(color: Colors.orange)),
                onTap: () {
                  Navigator.of(context).pop();
                  showMessageReportDialog(context, widget.msg.effectiveId);
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  void _showEmojiPicker(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetCtx) => Container(
        height: 280,
        decoration: BoxDecoration(
          color: Theme.of(sheetCtx).colorScheme.surface,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
        ),
        child: EmojiPicker(
          onEmojiSelected: (_, emoji) {
            Navigator.of(sheetCtx).pop();
            widget.onReaction?.call(emoji.emoji);
          },
          config: Config(
            height: 256,
            checkPlatformCompatibility: true,
            emojiViewConfig: EmojiViewConfig(
              columns: 8,
              emojiSizeMax: 28,
              backgroundColor: Theme.of(sheetCtx).colorScheme.surface,
            ),
            categoryViewConfig: CategoryViewConfig(
              backgroundColor: Theme.of(sheetCtx).colorScheme.surface,
              iconColorSelected: Theme.of(sheetCtx).colorScheme.primary,
              indicatorColor: Theme.of(sheetCtx).colorScheme.primary,
            ),
            bottomActionBarConfig: const BottomActionBarConfig(enabled: false),
            searchViewConfig: SearchViewConfig(
              backgroundColor: Theme.of(sheetCtx).colorScheme.surface,
            ),
          ),
        ),
      ),
    );
  }

  void _showContextMenu(BuildContext context, Offset position) {
    final canReport = !widget.isMine && !widget.msg.deleted;
    final items = <PopupMenuEntry<String>>[];
    if (widget.onReaction != null && !widget.msg.deleted) {
      items.add(PopupMenuItem(
        enabled: false,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            ..._quickReactions.map((e) => GestureDetector(
              onTap: () { Navigator.of(context).pop('react_$e'); },
              child: Text(e, style: const TextStyle(fontSize: 20)),
            )),
            GestureDetector(
              onTap: () {
                Navigator.of(context).pop('emoji_picker');
              },
              child: Container(
                width: 32, height: 32,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: const Icon(Icons.add, size: 18),
              ),
            ),
          ],
        ),
      ));
      items.add(const PopupMenuDivider());
    }
    if (widget.onReply != null) {
      items.add(const PopupMenuItem(value: 'reply', child: Row(children: [Icon(Icons.reply, size: 18), SizedBox(width: 8), Text('답글')])));
    }
    if (widget.onForward != null) {
      items.add(const PopupMenuItem(value: 'forward', child: Row(children: [Icon(Icons.forward_outlined, size: 18), SizedBox(width: 8), Text('전달')])));
    }
    if (widget.onPin != null) {
      items.add(PopupMenuItem(value: 'pin', child: Row(children: [Icon(widget.msg.pinned ? Icons.push_pin : Icons.push_pin_outlined, size: 18), const SizedBox(width: 8), Text(widget.msg.pinned ? '고정 해제' : '고정')])));
    }
    if (widget.onBookmark != null) {
      items.add(PopupMenuItem(value: 'bookmark', child: Row(children: [Icon(widget.isBookmarked ? Icons.bookmark : Icons.bookmark_border, size: 18), const SizedBox(width: 8), Text(widget.isBookmarked ? '북마크 해제' : '북마크')])));
    }
    if (!widget.msg.deleted) {
      items.add(const PopupMenuItem(value: 'copy', child: Row(children: [Icon(Icons.copy_outlined, size: 18), SizedBox(width: 8), Text('복사')])));
    }
    if (widget.onEdit != null) {
      items.add(const PopupMenuItem(value: 'edit', child: Row(children: [Icon(Icons.edit_outlined, size: 18), SizedBox(width: 8), Text('수정')])));
    }
    if (widget.onDelete != null) {
      items.add(const PopupMenuItem(value: 'delete', child: Row(children: [Icon(Icons.delete_outline, size: 18, color: Colors.red), SizedBox(width: 8), Text('삭제', style: TextStyle(color: Colors.red))])));
    }
    if (canReport) {
      items.add(const PopupMenuItem(value: 'report', child: Row(children: [Icon(Icons.flag_outlined, size: 18, color: Colors.orange), SizedBox(width: 8), Text('신고', style: TextStyle(color: Colors.orange))])));
    }
    if (items.isEmpty) return;
    showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(position.dx, position.dy, position.dx, position.dy),
      items: items,
    ).then((value) {
      if (value == null) return;
      if (value == 'emoji_picker') { if (context.mounted) _showEmojiPicker(context); return; }
      if (value.startsWith('react_')) { widget.onReaction?.call(value.substring(6)); return; }
      if (value == 'copy') {
        Clipboard.setData(ClipboardData(text: widget.msg.content));
        if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('메시지가 복사되었습니다.'), duration: Duration(seconds: 1)));
        return;
      }
      if (value == 'reply') widget.onReply?.call();
      if (value == 'forward') widget.onForward?.call();
      if (value == 'pin') widget.onPin?.call();
      if (value == 'bookmark') widget.onBookmark?.call();
      if (value == 'edit') widget.onEdit?.call();
      if (value == 'delete') widget.onDelete?.call();
      if (value == 'report') showMessageReportDialog(context, widget.msg.effectiveId);
    });
  }

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
              _Avatar(name: widget.msg.username, color: _avatarColor(widget.msg.username)),
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
    final hasActions = canReport || widget.onDelete != null || widget.onEdit != null || widget.onReply != null || widget.onReaction != null || widget.onForward != null || widget.onPin != null || widget.onBookmark != null;

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
              _Avatar(name: widget.msg.username, color: _avatarColor(widget.msg.username))
            else
              const SizedBox(width: 32), // placeholder for alignment
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Column(
              crossAxisAlignment:
                  widget.isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              children: [
                if (isUrgent)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 3),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.red.withAlpha(25),
                        borderRadius: BorderRadius.circular(4),
                        border: Border.all(color: Colors.red.withAlpha(120), width: 0.5),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.priority_high, size: 12, color: Colors.red),
                          const SizedBox(width: 2),
                          Text(
                            widget.msg.priority.toUpperCase(),
                            style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.red),
                          ),
                        ],
                      ),
                    ),
                  ),
                if (!widget.isMine && widget.showAvatar)
                  Padding(
                    padding: const EdgeInsets.only(left: 4, bottom: 3),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          widget.msg.username,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        if (widget.msg.priority == 'URGENT' || widget.msg.priority == 'STAT') ...[
                          const SizedBox(width: 5),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                            decoration: BoxDecoration(
                              color: widget.msg.priority == 'STAT'
                                  ? const Color(0xFFD32F2F).withAlpha(20)
                                  : const Color(0xFFF57C00).withAlpha(20),
                              borderRadius: BorderRadius.circular(3),
                              border: Border.all(
                                color: widget.msg.priority == 'STAT'
                                    ? const Color(0xFFD32F2F)
                                    : const Color(0xFFF57C00),
                                width: 0.5,
                              ),
                            ),
                            child: Text(
                              widget.msg.priority,
                              style: TextStyle(
                                fontSize: 9,
                                fontWeight: FontWeight.w700,
                                color: widget.msg.priority == 'STAT'
                                    ? const Color(0xFFD32F2F)
                                    : const Color(0xFFF57C00),
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                if (widget.isMine && (widget.msg.priority == 'URGENT' || widget.msg.priority == 'STAT'))
                  Padding(
                    padding: const EdgeInsets.only(right: 4, bottom: 3),
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                        decoration: BoxDecoration(
                          color: widget.msg.priority == 'STAT'
                              ? const Color(0xFFD32F2F).withAlpha(20)
                              : const Color(0xFFF57C00).withAlpha(20),
                          borderRadius: BorderRadius.circular(3),
                          border: Border.all(
                            color: widget.msg.priority == 'STAT' ? const Color(0xFFD32F2F) : const Color(0xFFF57C00),
                            width: 0.5,
                          ),
                        ),
                        child: Text(widget.msg.priority, style: TextStyle(
                          fontSize: 9, fontWeight: FontWeight.w700,
                          color: widget.msg.priority == 'STAT' ? const Color(0xFFD32F2F) : const Color(0xFFF57C00),
                        )),
                      ),
                    ),
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
                            if (widget.isMine && widget.msg.deliveryStatus == MessageDeliveryStatus.sending)
                              Padding(padding: const EdgeInsets.only(left: 3), child: Icon(Icons.schedule, size: 11, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120))),
                            if (widget.isMine && widget.msg.deliveryStatus == MessageDeliveryStatus.failed)
                              GestureDetector(
                                onTap: widget.onRetry,
                                child: const Padding(
                                    padding: EdgeInsets.only(left: 3),
                                    child: Tooltip(
                                      message: '재전송',
                                      child: Icon(Icons.refresh, size: 13, color: Colors.red),
                                    )),
                              ),
                          ],
                        ),
                      ),
                  ],
                ),
                // Reply count chip — shown when there are replies in the buffer
                if (widget.replyCount > 0 && widget.onOpenThread != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: GestureDetector(
                      onTap: () => widget.onOpenThread!(widget.msg),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: Theme.of(context)
                              .colorScheme
                              .primaryContainer
                              .withAlpha(60),
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: Theme.of(context)
                                .colorScheme
                                .primary
                                .withAlpha(80),
                            width: 1,
                          ),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.forum_outlined,
                              size: 13,
                              color: Theme.of(context).colorScheme.primary,
                            ),
                            const SizedBox(width: 4),
                            Text(
                              '${widget.replyCount}개 답글',
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                                color:
                                    Theme.of(context).colorScheme.primary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                // Reaction chips inside bubble area
                if (widget.msg.reactions.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Wrap(
                      spacing: 4,
                      children: widget.msg.reactions.entries.map((e) {
                        final emoji = e.key;
                        final users = e.value;
                        return GestureDetector(
                          onTap: () => widget.onReaction?.call(emoji),
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: Theme.of(context).colorScheme.surfaceContainer,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(color: Theme.of(context).colorScheme.outline.withAlpha(60)),
                            ),
                            child: Text('$emoji ${users.length}', style: const TextStyle(fontSize: 12)),
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                if (widget.onReply != null)
                  Align(
                    alignment: widget.isMine ? Alignment.centerRight : Alignment.centerLeft,
                    child: GestureDetector(
                      onTap: widget.onReply,
                      child: Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Icon(
                          Icons.reply_rounded,
                          size: 16,
                          color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120),
                        ),
                      ),
                    ),
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

    // Desktop/web hover reaction bar
    final showHoverBar = kIsWeb && widget.onReaction != null && !widget.msg.deleted;
    if (!showHoverBar) return result;

    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          result,
          if (_hovered)
            Positioned(
              top: -32,
              right: widget.isMine ? 0 : null,
              left: widget.isMine ? null : 0,
              child: _HoverReactionBar(
                reactions: _quickReactions,
                onReaction: widget.onReaction!,
              ),
            ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Desktop hover reaction bar (Slack/Discord style)
// ─────────────────────────────────────────────────────────────────
class _HoverReactionBar extends StatelessWidget {
  final List<String> reactions;
  final void Function(String emoji) onReaction;

  const _HoverReactionBar({required this.reactions, required this.onReaction});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [BoxShadow(color: Colors.black.withAlpha(30), blurRadius: 6, offset: const Offset(0, 2))],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: reactions.map((emoji) => GestureDetector(
          onTap: () => onReaction(emoji),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 3),
            child: Text(emoji, style: const TextStyle(fontSize: 18)),
          ),
        )).toList(),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Unread divider — "여기까지 읽었습니다"
// ─────────────────────────────────────────────────────────────────
class _UnreadDivider extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Expanded(child: Divider(color: Colors.red.withAlpha(120), height: 1)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Text(
              '여기까지 읽었습니다',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: Colors.red.withAlpha(180),
              ),
            ),
          ),
          Expanded(child: Divider(color: Colors.red.withAlpha(120), height: 1)),
        ],
      ),
    );
  }
}

// SBAR structured card
// ─────────────────────────────────────────────────────────────────
class _SbarCardWidget extends StatelessWidget {
  final Map<String, dynamic> sbar;
  const _SbarCardWidget({required this.sbar});

  static const _sections = [
    ('S', 'Situation', Color(0xFF1976D2)),
    ('B', 'Background', Color(0xFF388E3C)),
    ('A', 'Assessment', Color(0xFFF57C00)),
    ('R', 'Recommendation', Color(0xFFD32F2F)),
  ];

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    final cardMaxWidth = screenWidth < 600
        ? (screenWidth * 0.70).clamp(180.0, 320.0)
        : 320.0;
    return Container(
      constraints: BoxConstraints(maxWidth: cardMaxWidth),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Theme.of(context).colorScheme.outline.withAlpha(80)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primaryContainer.withAlpha(80),
              borderRadius: const BorderRadius.vertical(top: Radius.circular(11)),
            ),
            child: const Row(
              children: [
                Icon(Icons.assignment_outlined, size: 16),
                SizedBox(width: 6),
                Flexible(child: Text('SBAR 인수인계', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13))),
              ],
            ),
          ),
          for (final sec in _sections)
            if ((sbar[sec.$1.toLowerCase() == 's' ? 'situation' : sec.$1.toLowerCase() == 'b' ? 'background' : sec.$1.toLowerCase() == 'a' ? 'assessment' : 'recommendation'] ?? '').toString().isNotEmpty)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  border: Border(left: BorderSide(color: sec.$3, width: 3)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${sec.$1} - ${sec.$2}', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: sec.$3)),
                    const SizedBox(height: 2),
                    Text(
                      sbar[sec.$1.toLowerCase() == 's' ? 'situation' : sec.$1.toLowerCase() == 'b' ? 'background' : sec.$1.toLowerCase() == 'a' ? 'assessment' : 'recommendation']?.toString() ?? '',
                      style: const TextStyle(fontSize: 13, height: 1.4),
                    ),
                  ],
                ),
              ),
          const SizedBox(height: 6),
        ],
      ),
    );
  }
}

// Link preview card
// ─────────────────────────────────────────────────────────────────
class _LinkPreviewCard extends StatefulWidget {
  final String url;
  const _LinkPreviewCard({required this.url});

  @override
  State<_LinkPreviewCard> createState() => _LinkPreviewCardState();
}

class _LinkPreviewCardState extends State<_LinkPreviewCard> {
  /// Static memory cache to avoid redundant API calls for the same URL.
  static final Map<String, Map<String, String>> _cache = {};

  /// Sentinel value stored in cache when the API fails, to avoid retrying.
  static const Map<String, String> _empty = {};

  Map<String, String>? _ogData;
  bool _loading = true;

  static String _apiBase() {
    if (kIsWeb) {
      final uri = Uri.base;
      final port = (uri.hasPort && uri.port != 80 && uri.port != 443) ? ':${uri.port}' : '';
      return '${uri.scheme}://${uri.host}$port';
    }
    // Native: rely on the same env-based base URL as DioClient.
    // flutter_dotenv may not be initialized here, so fall back to production URL.
    try {
      // ignore: undefined_prefixed_name
      return const String.fromEnvironment('API_BASE_URL', defaultValue: 'https://app.chatflow.ai.kr');
    } catch (_) {
      return 'https://app.chatflow.ai.kr';
    }
  }

  @override
  void initState() {
    super.initState();
    _fetchOg();
  }

  Future<void> _fetchOg() async {
    final url = widget.url;

    if (_cache.containsKey(url)) {
      final cached = _cache[url]!;
      if (mounted) setState(() { _ogData = cached.isEmpty ? null : cached; _loading = false; });
      return;
    }

    try {
      final dio = Dio(BaseOptions(
        baseUrl: _apiBase(),
        connectTimeout: const Duration(seconds: 5),
        receiveTimeout: const Duration(seconds: 10),
      ));
      final resp = await dio.get(
        '/api/chat/rooms/link-preview',
        queryParameters: {'url': url},
      );
      final data = resp.data;
      Map<String, String>? parsed;
      if (data is Map && data['data'] is Map) {
        parsed = Map<String, String>.from((data['data'] as Map).map(
          (k, v) => MapEntry(k.toString(), v?.toString() ?? ''),
        ));
      }
      _cache[url] = parsed ?? _empty;
      if (mounted) setState(() { _ogData = (parsed?.isNotEmpty == true) ? parsed : null; _loading = false; });
    } catch (_) {
      _cache[url] = _empty;
      if (mounted) setState(() { _loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final host = Uri.tryParse(widget.url)?.host ?? widget.url;
    final maxWidth = MediaQuery.of(context).size.width < 600
        ? MediaQuery.of(context).size.width * 0.65
        : 300.0;

    Widget cardContent;
    if (!_loading && _ogData != null) {
      final title = _ogData!['title'] ?? '';
      final description = _ogData!['description'] ?? '';
      final image = _ogData!['image'] ?? '';
      cardContent = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (image.isNotEmpty)
            ClipRRect(
              borderRadius: const BorderRadius.vertical(top: Radius.circular(7)),
              child: Image.network(
                image,
                height: 120,
                width: double.infinity,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const SizedBox.shrink(),
              ),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(host,
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (title.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(title,
                    style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: cs.onSurface),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                if (description.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(description,
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ],
            ),
          ),
        ],
      );
    } else {
      // Fallback: host-only display (also used while loading)
      cardContent = Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        child: Row(
          children: [
            Icon(Icons.link, size: 16, color: cs.primary),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(host, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: cs.primary)),
                  Text(widget.url, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
                ],
              ),
            ),
            Icon(Icons.open_in_new, size: 14, color: cs.onSurfaceVariant.withAlpha(120)),
          ],
        ),
      );
    }

    return GestureDetector(
      onTap: () async {
        final uri = Uri.parse(widget.url);
        if (await canLaunchUrl(uri)) await launchUrl(uri, mode: LaunchMode.externalApplication);
      },
      child: Container(
        margin: const EdgeInsets.only(top: 6),
        constraints: BoxConstraints(maxWidth: maxWidth),
        decoration: BoxDecoration(
          color: cs.surfaceContainer,
          borderRadius: BorderRadius.circular(8),
          border: Border(left: BorderSide(color: cs.primary, width: 3)),
        ),
        child: cardContent,
      ),
    );
  }
}

// Date divider
// ─────────────────────────────────────────────────────────────────
class _DateDivider extends StatelessWidget {
  final String date;
  const _DateDivider({required this.date});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(child: Divider(color: cs.outline.withAlpha(60), height: 1)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: cs.surfaceContainer,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: cs.outline.withAlpha(60)),
              ),
              child: Text(
                date,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w500,
                  color: cs.onSurfaceVariant.withAlpha(180),
                ),
              ),
            ),
          ),
          Expanded(child: Divider(color: cs.outline.withAlpha(60), height: 1)),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Patient card bubble
// ─────────────────────────────────────────────────────────────────
class _PatientCardBubble extends StatelessWidget {
  final ChatMessage msg;
  final PatientCard card;
  final bool isMine;
  final String time;
  final int readCount;

  const _PatientCardBubble({
    required this.msg,
    required this.card,
    required this.isMine,
    required this.time,
    this.readCount = 0,
  });

  Color _avatarColor(String name) =>
      AppColors.avatarPalette[name.hashCode.abs() % AppColors.avatarPalette.length];

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment:
            isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isMine) ...[
            _Avatar(
              name: msg.username,
              color: _avatarColor(msg.username),
            ),
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Column(
              crossAxisAlignment:
                  isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              children: [
                if (!isMine)
                  Padding(
                    padding: const EdgeInsets.only(left: 4, bottom: 3),
                    child: Text(
                      msg.username,
                      style: TextStyle(
                        fontSize: 11,
                        color: cs.onSurfaceVariant,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (isMine)
                      Padding(
                        padding: const EdgeInsets.only(right: 5, bottom: 3),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (readCount > 0)
                              Text(
                                '읽음 $readCount',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: cs.primary.withAlpha(180),
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            Text(
                              time,
                              style: TextStyle(
                                fontSize: 10,
                                color: cs.onSurfaceVariant.withAlpha(140),
                              ),
                            ),
                          ],
                        ),
                      ),
                    PatientCardWidget(card: card, isMine: isMine),
                    if (!isMine)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, bottom: 3),
                        child: Text(
                          time,
                          style: TextStyle(
                            fontSize: 10,
                            color: cs.onSurfaceVariant.withAlpha(140),
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
          if (isMine) const SizedBox(width: 6),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// User avatar (gradient circle)
// ─────────────────────────────────────────────────────────────────
class _Avatar extends StatelessWidget {
  final String name;
  final Color color;
  const _Avatar({required this.name, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 30,
      height: 30,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [color.withAlpha(220), color.withAlpha(140)],
        ),
      ),
      child: Center(
        child: Text(
          name.isNotEmpty ? name[0].toUpperCase() : '?',
          style: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// File bubble (image preview or download button)
// ─────────────────────────────────────────────────────────────────
class _FileBubble extends StatelessWidget {
  final ChatMessage msg;
  final bool isMine;
  final String time;
  final int readCount;

  const _FileBubble({
    required this.msg,
    required this.isMine,
    required this.time,
    this.readCount = 0,
  });

  Color _avatarColor(String name) =>
      AppColors.avatarPalette[name.hashCode.abs() % AppColors.avatarPalette.length];


  Future<void> _launchUrl(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  void _showFullscreenImage(BuildContext context, String url) {
    showDialog(
      context: context,
      builder: (_) => Dialog.fullscreen(
        backgroundColor: Colors.black,
        child: Stack(
          children: [
            Center(
              child: InteractiveViewer(
                minScale: 0.5,
                maxScale: 4.0,
                child: Image.network(url, fit: BoxFit.contain,
                  errorBuilder: (_, __, ___) => const Icon(Icons.broken_image, color: Colors.white54, size: 64)),
              ),
            ),
            Positioned(
              top: 16, right: 16,
              child: IconButton(
                icon: const Icon(Icons.close, color: Colors.white, size: 28),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ),
            Positioned(
              bottom: 16, right: 16,
              child: IconButton(
                icon: const Icon(Icons.open_in_new, color: Colors.white70, size: 24),
                tooltip: '브라우저에서 열기',
                onPressed: () => _launchUrl(url),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final screenWidth = MediaQuery.of(context).size.width;
    // Responsive image width for mobile
    final imageWidth = screenWidth < 600
        ? (screenWidth * 0.55).clamp(140.0, 220.0)
        : 220.0;
    final radius = BorderRadius.only(
      topLeft: const Radius.circular(20),
      topRight: const Radius.circular(20),
      bottomLeft: Radius.circular(isMine ? 20 : 5),
      bottomRight: Radius.circular(isMine ? 5 : 20),
    );
    final fullUrl = buildFullUrl(msg.fileUrl!);

    // Text content that user typed (not default [파일] prefix)
    final hasTextContent = msg.content.isNotEmpty &&
        !msg.content.startsWith('[파일]');

    Widget content;
    if (msg.isImageFile) {
      content = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          GestureDetector(
            onTap: () => _showFullscreenImage(context, fullUrl),
            child: ClipRRect(
              borderRadius: hasTextContent
                  ? BorderRadius.only(topLeft: radius.topLeft, topRight: radius.topRight)
                  : radius,
              child: Image.network(
                fullUrl,
                width: imageWidth,
                fit: BoxFit.cover,
                loadingBuilder: (_, child, progress) {
                  if (progress == null) return child;
                  return Container(
                    width: imageWidth,
                    height: 160,
                    decoration: BoxDecoration(
                      color: cs.surfaceContainer,
                      borderRadius: radius,
                    ),
                    child: Center(
                      child: CircularProgressIndicator(
                        value: progress.expectedTotalBytes != null
                            ? progress.cumulativeBytesLoaded / progress.expectedTotalBytes!
                            : null,
                        strokeWidth: 2,
                      ),
                    ),
                  );
                },
                errorBuilder: (_, __, ___) => Container(
                  width: imageWidth,
                  height: 80,
                  decoration: BoxDecoration(
                    color: cs.surfaceContainer,
                    borderRadius: radius,
                    border: Border.all(color: cs.outline.withAlpha(80)),
                  ),
                  child: Center(
                    child: Icon(Icons.broken_image_outlined, color: cs.onSurfaceVariant),
                  ),
                ),
              ),
            ),
          ),
          if (hasTextContent)
            Container(
              width: imageWidth,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: isMine ? null : cs.surfaceContainer,
                gradient: isMine ? AppColors.myBubbleGradient : null,
                borderRadius: BorderRadius.only(
                  bottomLeft: radius.bottomLeft,
                  bottomRight: radius.bottomRight,
                ),
                border: isMine ? null : Border.all(color: cs.outline.withAlpha(80)),
              ),
              child: Text(
                msg.content,
                style: TextStyle(
                  fontSize: 14,
                  color: isMine ? Colors.white : cs.onSurface,
                  height: 1.4,
                ),
              ),
            ),
        ],
      );
    } else if (msg.isPdfFile) {
      final fileMaxWidth = screenWidth < 600
          ? (screenWidth * 0.65).clamp(160.0, 260.0)
          : 260.0;
      content = GestureDetector(
        onTap: () => PdfViewerDialog.open(context, fullUrl, msg.fileName ?? 'PDF'),
        child: Container(
          constraints: BoxConstraints(maxWidth: fileMaxWidth),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: isMine ? null : cs.surfaceContainer,
            gradient: isMine ? AppColors.myBubbleGradient : null,
            borderRadius: radius,
            border: isMine ? null : Border.all(color: cs.outline.withAlpha(80)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 40, height: 40,
                decoration: BoxDecoration(
                  color: Colors.red.withAlpha(25),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(Icons.picture_as_pdf, size: 24, color: Colors.red),
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      msg.fileName ?? 'PDF',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: isMine ? Colors.white : cs.onSurface,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'PDF 미리보기',
                      style: TextStyle(
                        fontSize: 11,
                        color: isMine ? Colors.white.withAlpha(200) : cs.primary,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right,
                size: 20,
                color: isMine ? Colors.white.withAlpha(180) : cs.onSurfaceVariant,
              ),
            ],
          ),
        ),
      );
    } else {
      final fileMaxWidth = screenWidth < 600
          ? (screenWidth * 0.65).clamp(160.0, 260.0)
          : 260.0;
      content = Container(
        constraints: BoxConstraints(maxWidth: fileMaxWidth),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: isMine ? null : cs.surfaceContainer,
          gradient: isMine ? AppColors.myBubbleGradient : null,
          borderRadius: radius,
          border: isMine ? null : Border.all(color: cs.outline.withAlpha(80)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.insert_drive_file_outlined,
              size: 28,
              color: isMine ? Colors.white.withAlpha(220) : cs.onSurfaceVariant,
            ),
            const SizedBox(width: 10),
            Flexible(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    msg.fileName ?? '파일',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: isMine ? Colors.white : cs.onSurface,
                    ),
                  ),
                  const SizedBox(height: 4),
                  GestureDetector(
                    onTap: () => _launchUrl(fullUrl),
                    child: Text(
                      '다운로드',
                      style: TextStyle(
                        fontSize: 11,
                        color: isMine
                            ? Colors.white.withAlpha(200)
                            : cs.primary,
                        decoration: TextDecoration.underline,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isMine) ...[
            _Avatar(name: msg.username, color: _avatarColor(msg.username)),
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Column(
              crossAxisAlignment: isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              children: [
                if (!isMine)
                  Padding(
                    padding: const EdgeInsets.only(left: 4, bottom: 3),
                    child: Text(
                      msg.username,
                      style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (isMine)
                      Padding(
                        padding: const EdgeInsets.only(right: 5, bottom: 3),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (readCount > 0)
                              Text(
                                '읽음 $readCount',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: Theme.of(context).colorScheme.primary.withAlpha(180),
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            Text(
                              time,
                              style: TextStyle(
                                fontSize: 10,
                                color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140),
                              ),
                            ),
                          ],
                        ),
                      ),
                    Flexible(child: content),
                    if (!isMine)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, bottom: 3),
                        child: Text(
                          time,
                          style: TextStyle(
                            fontSize: 10,
                            color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140),
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
          if (isMine) const SizedBox(width: 6),
        ],
      ),
    );
  }
}
