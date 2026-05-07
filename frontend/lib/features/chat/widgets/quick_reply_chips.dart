import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../quick_reply_provider.dart';

class QuickReplyChips extends ConsumerWidget {
  final String roomId;
  final void Function(String suggestion) onTap;

  const QuickReplyChips({
    super.key,
    required this.roomId,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(quickReplyProvider(roomId));
    if (state.isEmpty) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;
    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        itemCount: state.suggestions.length,
        separatorBuilder: (_, __) => const SizedBox(width: 6),
        itemBuilder: (ctx, i) {
          final text = state.suggestions[i];
          return ActionChip(
            label: Text(text, style: const TextStyle(fontSize: 13)),
            backgroundColor: cs.surfaceContainer,
            side: BorderSide(color: cs.outline.withAlpha(80)),
            visualDensity: VisualDensity.compact,
            onPressed: () => onTap(text),
          );
        },
      ),
    );
  }
}
