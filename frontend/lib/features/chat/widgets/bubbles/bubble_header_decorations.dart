import 'package:flutter/material.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../shared/models/chat_message.dart';

// ─────────────────────────────────────────────────────────────────
// Header decorations: forwarded chip, username label
// ─────────────────────────────────────────────────────────────────
class BubbleHeaderDecorations extends StatelessWidget {
  final ChatMessage msg;
  final bool isMine;
  final bool showAvatar;

  const BubbleHeaderDecorations({
    super.key,
    required this.msg,
    required this.isMine,
    required this.showAvatar,
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
                borderRadius: BorderRadius.circular(10),
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
        // Username label (other user's bubble)
        if (!isMine && showAvatar)
          Padding(
            padding: const EdgeInsets.only(left: 2, bottom: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    msg.username,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: ChatPalette.of(context).name,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
