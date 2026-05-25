import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';

// ─────────────────────────────────────────────────────────────────
// System message (JOIN / LEAVE / SYSTEM)
// ─────────────────────────────────────────────────────────────────
class SystemBubble extends StatelessWidget {
  final ChatMessage msg;
  const SystemBubble({super.key, required this.msg});

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
