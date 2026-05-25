import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../chat_provider.dart';

// ---------------------------------------------------------------------------
// AI summary request button
// ---------------------------------------------------------------------------
class AiSummaryButton extends ConsumerStatefulWidget {
  final String roomId;
  const AiSummaryButton({super.key, required this.roomId});

  @override
  ConsumerState<AiSummaryButton> createState() => AiSummaryButtonState();
}

class AiSummaryButtonState extends ConsumerState<AiSummaryButton> {
  Future<void> _onTap() async {
    try {
      final msg = await ref
          .read(chatNotifierProvider(widget.roomId).notifier)
          .requestSummary(widget.roomId);
      if (!mounted) return;
      if (msg.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(msg), duration: const Duration(seconds: 3)),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('AI 요약을 요청했습니다. 잠시 후 채팅방에 표시됩니다.'),
            duration: Duration(seconds: 3),
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('요약 요청에 실패했습니다.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isSummaryLoading = ref.watch(
      chatNotifierProvider(widget.roomId).select((s) => s.isSummaryLoading),
    );
    return IconButton(
      icon: isSummaryLoading
          ? const SizedBox(
              width: 18, height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.auto_awesome, size: 20),
      tooltip: 'AI 대화 요약',
      onPressed: isSummaryLoading ? null : _onTap,
    );
  }
}
