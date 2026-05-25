import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';

// ─────────────────────────────────────────────────────────────────
// Header decorations: forwarded chip, urgent badge,
// username+priority label, my-priority badge
// ─────────────────────────────────────────────────────────────────
class BubbleHeaderDecorations extends StatelessWidget {
  final ChatMessage msg;
  final bool isMine;
  final bool showAvatar;
  final bool isUrgent;

  const BubbleHeaderDecorations({
    super.key,
    required this.msg,
    required this.isMine,
    required this.showAvatar,
    required this.isUrgent,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
        // Forwarded chip
        if (msg.forwardedFrom != null && msg.forwardedFrom!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 3),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.secondaryContainer.withAlpha(160),
                borderRadius: BorderRadius.circular(4),
                border: Border.all(
                  color: Theme.of(context).colorScheme.secondary.withAlpha(120),
                  width: 0.5,
                ),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.forward_outlined,
                    size: 12,
                    color: Theme.of(context).colorScheme.onSecondaryContainer,
                  ),
                  const SizedBox(width: 3),
                  Text(
                    '전달됨 · ${msg.forwardedFrom!}',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      color: Theme.of(context).colorScheme.onSecondaryContainer,
                    ),
                  ),
                ],
              ),
            ),
          ),
        // Urgent badge
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
                    msg.priority.toUpperCase(),
                    style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.red),
                  ),
                ],
              ),
            ),
          ),
        // Username + priority label (other user's bubble)
        if (!isMine && showAvatar)
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
        // My-priority badge (own bubble)
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
      ],
    );
  }
}
