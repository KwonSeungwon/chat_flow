import 'dart:async';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/chat_message.dart';

class ChatMessagesList extends StatefulWidget {
  final List<ChatMessage> messages;
  final String currentUsername;
  final bool isAiLoading;

  const ChatMessagesList({
    super.key,
    required this.messages,
    required this.currentUsername,
    this.isAiLoading = false,
  });

  @override
  State<ChatMessagesList> createState() => _ChatMessagesListState();
}

class _ChatMessagesListState extends State<ChatMessagesList> {
  final _scrollController = ScrollController();
  bool _autoScroll = true;
  int _unreadCount = 0;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(() {
      if (!_scrollController.hasClients) return;
      final atBottom = _scrollController.offset >=
          _scrollController.position.maxScrollExtent - 80;
      if (atBottom && !_autoScroll) {
        setState(() {
          _autoScroll = true;
          _unreadCount = 0;
        });
      } else if (!atBottom && _autoScroll) {
        _autoScroll = false;
      }
    });
  }

  @override
  void didUpdateWidget(covariant ChatMessagesList oldWidget) {
    super.didUpdateWidget(oldWidget);
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

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.messages.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppColors.surfaceHigh,
                border: Border.all(color: AppColors.border),
              ),
              child: const Icon(Icons.chat_bubble_outline_rounded,
                  size: 30, color: AppColors.textMuted),
            ),
            const SizedBox(height: 16),
            const Text(
              '아직 메시지가 없습니다',
              style: TextStyle(
                  color: AppColors.textSecondary,
                  fontWeight: FontWeight.w500,
                  fontSize: 15),
            ),
            const SizedBox(height: 4),
            const Text(
              '첫 메시지를 보내보세요!',
              style: TextStyle(color: AppColors.textMuted, fontSize: 13),
            ),
          ],
        ),
      );
    }

    return Stack(
      children: [
        ListView.builder(
          controller: _scrollController,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          itemCount: widget.messages.length + (widget.isAiLoading ? 1 : 0),
          itemBuilder: (context, index) {
            // AI loading indicator at the bottom
            if (index == widget.messages.length && widget.isAiLoading) {
              return const _AiLoadingBubble();
            }
            final msg = widget.messages[index];
            final type = msg.type.toUpperCase();

            if (type == 'JOIN' || type == 'LEAVE' || type == 'SYSTEM') {
              return _SystemBubble(msg: msg);
            }
            if (type == 'AI_SUMMARY' || msg.isAiGenerated) {
              return _AiSummaryCard(msg: msg);
            }
            final isAiQuestion = msg.effectiveId.startsWith('ai-q-');
            return _ChatBubble(
              msg: msg,
              isMine: msg.username == widget.currentUsername,
              time: _formatTime(msg.timestamp),
              isAiQuestion: isAiQuestion,
            );
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

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Center(
        child: Container(
          padding:
              const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainer,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: Theme.of(context).colorScheme.outline.withAlpha(80)),
          ),
          child: Text(
            _text,
            style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(160)),
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
    _timer = Timer.periodic(const Duration(milliseconds: 30), (_) {
      if (!mounted) return;
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
                  constraints: const BoxConstraints(maxWidth: 320),
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
class _ChatBubble extends StatelessWidget {
  final ChatMessage msg;
  final bool isMine;
  final String time;
  final bool isAiQuestion;

  const _ChatBubble({
    required this.msg,
    required this.isMine,
    required this.time,
    this.isAiQuestion = false,
  });

  Color _avatarColor(String name) =>
      AppColors.avatarPalette[name.hashCode.abs() % AppColors.avatarPalette.length];

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.only(
      topLeft: const Radius.circular(20),
      topRight: const Radius.circular(20),
      bottomLeft: Radius.circular(isMine ? 20 : 5),
      bottomRight: Radius.circular(isMine ? 5 : 20),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment:
            isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isMine) ...[
            _Avatar(name: msg.username, color: _avatarColor(msg.username)),
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
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
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
                        child: Text(time,
                            style: TextStyle(
                                fontSize: 10, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140))),
                      ),
                    Flexible(
                      child: isMine
                          ? Container(
                              constraints:
                                  const BoxConstraints(maxWidth: 320),
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 14, vertical: 10),
                              decoration: BoxDecoration(
                                gradient: isAiQuestion
                                    ? const LinearGradient(
                                        colors: [Color(0xFF7C3AED), Color(0xFF6D28D9)],
                                      )
                                    : AppColors.myBubbleGradient,
                                borderRadius: radius,
                                boxShadow: [
                                  BoxShadow(
                                    color: (isAiQuestion ? const Color(0xFF7C3AED) : AppColors.primary).withAlpha(50),
                                    blurRadius: 10,
                                    offset: const Offset(0, 3),
                                  ),
                                ],
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  if (isAiQuestion)
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
                                  Text(
                                    msg.content,
                                    style: const TextStyle(
                                        color: Colors.white,
                                        fontSize: 14,
                                        height: 1.4),
                                  ),
                                ],
                              ),
                            )
                          : Container(
                              constraints:
                                  const BoxConstraints(maxWidth: 320),
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 14, vertical: 10),
                              decoration: BoxDecoration(
                                color: Theme.of(context).colorScheme.surfaceContainer,
                                borderRadius: radius,
                                border: Border.all(
                                    color: Theme.of(context).colorScheme.outline.withAlpha(80), width: 1),
                              ),
                              child: Text(
                                msg.content,
                                style: TextStyle(
                                    color: Theme.of(context).colorScheme.onSurface,
                                    fontSize: 14,
                                    height: 1.4),
                              ),
                            ),
                    ),
                    if (!isMine)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, bottom: 3),
                        child: Text(time,
                            style: TextStyle(
                                fontSize: 10, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140))),
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
