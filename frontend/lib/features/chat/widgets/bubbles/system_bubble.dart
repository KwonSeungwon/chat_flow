import 'package:flutter/material.dart';
import '../../../../core/theme/app_theme.dart';
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

  @override
  Widget build(BuildContext context) {
    final cp = ChatPalette.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Center(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.85,
          ),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
            decoration: BoxDecoration(
              color: cp.pill,
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    _text,
                    style: TextStyle(
                      fontSize: 12,
                      color: cp.onPill,
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
