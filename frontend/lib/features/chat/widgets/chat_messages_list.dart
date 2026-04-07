import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/chat_message.dart';
import '../../../shared/models/patient_card.dart';
import 'patient_card_widget.dart';

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

  const ChatMessagesList({
    super.key,
    required this.messages,
    required this.currentUsername,
    this.isAiLoading = false,
    this.readCounts = const {},
    this.scrollToMessageId,
    this.highlightMessageId,
  });

  @override
  State<ChatMessagesList> createState() => _ChatMessagesListState();
}

class _ChatMessagesListState extends State<ChatMessagesList> {
  final _scrollController = ScrollController();
  final _targetKey = GlobalKey();
  bool _autoScroll = true;
  int _unreadCount = 0;
  String? _highlightedMessageId;
  Timer? _highlightTimer;
  String? _lastScrollTarget;

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
    // If messages are already loaded when widget mounts (e.g. provider still alive),
    // attempt scroll immediately on the first frame.
    if (widget.scrollToMessageId != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _tryScrollToTarget();
      });
    }
  }

  /// Attempt to scroll to [widget.scrollToMessageId] if it exists in the list.
  void _tryScrollToTarget() {
    final target = widget.scrollToMessageId;
    if (target == null || target == _lastScrollTarget) return;
    final hasTarget = widget.messages.any((m) => m.effectiveId == target);
    if (hasTarget) {
      _lastScrollTarget = target;
      _autoScroll = false;
      _scheduleScrollToTarget();
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

  @override
  void didUpdateWidget(covariant ChatMessagesList oldWidget) {
    super.didUpdateWidget(oldWidget);

    // When messages load and there's a scroll target, scroll to it once
    if (widget.scrollToMessageId != null &&
        widget.messages.length != oldWidget.messages.length) {
      _tryScrollToTarget();
      if (_lastScrollTarget == widget.scrollToMessageId) return;
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
            final isTarget = msg.effectiveId == widget.scrollToMessageId;
            final isHighlighted = msg.effectiveId == _highlightedMessageId;

            Widget item;
            if (type == 'JOIN' || type == 'LEAVE' || type == 'SYSTEM') {
              item = _SystemBubble(msg: msg);
            } else if (type == 'AI_SUMMARY' || msg.isAiGenerated) {
              item = _AiSummaryCard(msg: msg);
            } else if (type == 'PATIENT_CARD') {
              final card = PatientCard.tryParseContent(msg.content);
              if (card != null) {
                final isMine = msg.username == widget.currentUsername;
                final readCount = widget.readCounts[msg.effectiveId] ?? 0;
                item = _PatientCardBubble(
                  msg: msg,
                  card: card,
                  isMine: isMine,
                  time: _formatTime(msg.timestamp),
                  readCount: readCount,
                );
              } else {
                item = const SizedBox.shrink();
              }
            } else if (msg.isFileMessage) {
              final isMine = msg.username == widget.currentUsername;
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              item = _FileBubble(
                msg: msg,
                isMine: isMine,
                time: _formatTime(msg.timestamp),
                readCount: readCount,
              );
            } else {
              final isAiQuestion = msg.content.startsWith('[AI에게] ');
              final readCount = widget.readCounts[msg.effectiveId] ?? 0;
              item = _ChatBubble(
                msg: msg,
                isMine: msg.username == widget.currentUsername,
                time: _formatTime(msg.timestamp),
                isAiQuestion: isAiQuestion,
                readCount: readCount,
              );
            }

            // Wrap with highlight overlay for search-navigated messages
            if (isHighlighted) {
              item = AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                decoration: BoxDecoration(
                  color: Colors.amber.withAlpha(isHighlighted ? 45 : 0),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: item,
              );
            }

            // Wrap with GlobalKey so we can scroll to this item
            if (isTarget) {
              return KeyedSubtree(key: _targetKey, child: item);
            }
            return item;
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
              Text(
                _text,
                style: TextStyle(
                  fontSize: 12,
                  color: cs.onSurfaceVariant.withAlpha(160),
                ),
              ),
            ],
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
  final int readCount;

  const _ChatBubble({
    required this.msg,
    required this.isMine,
    required this.time,
    this.isAiQuestion = false,
    this.readCount = 0,
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
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          msg.username,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        if (msg.priority == 'URGENT' || msg.priority == 'STAT') ...[
                          const SizedBox(width: 5),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                            decoration: BoxDecoration(
                              color: msg.priority == 'STAT'
                                  ? const Color(0xFFD32F2F).withAlpha(20)
                                  : const Color(0xFFF57C00).withAlpha(20),
                              borderRadius: BorderRadius.circular(3),
                              border: Border.all(
                                color: msg.priority == 'STAT'
                                    ? const Color(0xFFD32F2F)
                                    : const Color(0xFFF57C00),
                                width: 0.5,
                              ),
                            ),
                            child: Text(
                              msg.priority,
                              style: TextStyle(
                                fontSize: 9,
                                fontWeight: FontWeight.w700,
                                color: msg.priority == 'STAT'
                                    ? const Color(0xFFD32F2F)
                                    : const Color(0xFFF57C00),
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                if (isMine && (msg.priority == 'URGENT' || msg.priority == 'STAT'))
                  Padding(
                    padding: const EdgeInsets.only(right: 4, bottom: 3),
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                        decoration: BoxDecoration(
                          color: msg.priority == 'STAT'
                              ? const Color(0xFFD32F2F).withAlpha(20)
                              : const Color(0xFFF57C00).withAlpha(20),
                          borderRadius: BorderRadius.circular(3),
                          border: Border.all(
                            color: msg.priority == 'STAT' ? const Color(0xFFD32F2F) : const Color(0xFFF57C00),
                            width: 0.5,
                          ),
                        ),
                        child: Text(msg.priority, style: TextStyle(
                          fontSize: 9, fontWeight: FontWeight.w700,
                          color: msg.priority == 'STAT' ? const Color(0xFFD32F2F) : const Color(0xFFF57C00),
                        )),
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
                                    isAiQuestion ? msg.content.replaceFirst('[AI에게] ', '') : msg.content,
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

  String _buildFullUrl(String relativeUrl) {
    if (kIsWeb) {
      final uri = Uri.base;
      final port = (uri.hasPort && uri.port != 80 && uri.port != 443) ? ':${uri.port}' : '';
      return '${uri.scheme}://${uri.host}$port$relativeUrl';
    }
    final base = dotenv.env['API_BASE_URL'] ?? 'http://43.201.94.100:8000';
    return '$base$relativeUrl';
  }

  Future<void> _launchUrl(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final radius = BorderRadius.only(
      topLeft: const Radius.circular(20),
      topRight: const Radius.circular(20),
      bottomLeft: Radius.circular(isMine ? 20 : 5),
      bottomRight: Radius.circular(isMine ? 5 : 20),
    );
    final fullUrl = _buildFullUrl(msg.fileUrl!);

    Widget content;
    if (msg.isImageFile) {
      content = GestureDetector(
        onTap: () => _launchUrl(fullUrl),
        child: ClipRRect(
          borderRadius: radius,
          child: Image.network(
            fullUrl,
            width: 220,
            fit: BoxFit.cover,
            loadingBuilder: (_, child, progress) {
              if (progress == null) return child;
              return Container(
                width: 220,
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
              width: 220,
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
      );
    } else {
      content = Container(
        constraints: const BoxConstraints(maxWidth: 260),
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
                    content,
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
