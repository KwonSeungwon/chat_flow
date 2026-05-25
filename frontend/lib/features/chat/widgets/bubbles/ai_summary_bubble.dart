import 'dart:async';
import 'package:flutter/material.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../shared/models/chat_message.dart';

class AiSummaryCard extends StatefulWidget {
  final ChatMessage msg;
  const AiSummaryCard({super.key, required this.msg});

  @override
  State<AiSummaryCard> createState() => AiSummaryCardState();
}

class AiSummaryCardState extends State<AiSummaryCard> {
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
                        TypingDots(),
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

class TypingDots extends StatefulWidget {
  const TypingDots({super.key});

  @override
  State<TypingDots> createState() => TypingDotsState();
}

class TypingDotsState extends State<TypingDots>
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
class AiLoadingBubble extends StatelessWidget {
  const AiLoadingBubble({super.key});

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
