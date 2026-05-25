import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';

// ─────────────────────────────────────────────────────────────────
// Bubble footer: reply count chip, reaction chips, reply icon
// ─────────────────────────────────────────────────────────────────
class BubbleFooter extends StatelessWidget {
  final ChatMessage msg;
  final bool isMine;
  final int replyCount;
  final void Function(ChatMessage parent)? onOpenThread;
  final void Function(String emoji)? onReaction;
  final VoidCallback? onReply;

  const BubbleFooter({
    super.key,
    required this.msg,
    required this.isMine,
    required this.replyCount,
    this.onOpenThread,
    this.onReaction,
    this.onReply,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
        // Reply count chip
        if (replyCount > 0 && onOpenThread != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: GestureDetector(
              onTap: () => onOpenThread!(msg),
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
                      '$replyCount개 답글',
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
        // Reaction chips
        if (msg.reactions.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Wrap(
              spacing: 4,
              children: msg.reactions.entries.map((e) {
                final emoji = e.key;
                final users = e.value;
                return GestureDetector(
                  onTap: () => onReaction?.call(emoji),
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
        // Reply icon
        if (onReply != null)
          Align(
            alignment: isMine ? Alignment.centerRight : Alignment.centerLeft,
            child: GestureDetector(
              onTap: onReply,
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
    );
  }
}
