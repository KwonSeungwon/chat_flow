import 'package:flutter/material.dart';
import '../../../../shared/models/chat_message.dart';
import '../../../../shared/models/patient_card.dart';
import '../patient_card_widget.dart';
import 'avatar.dart';

class PatientCardBubble extends StatelessWidget {
  final ChatMessage msg;
  final PatientCard card;
  final bool isMine;
  final String time;
  final int readCount;

  const PatientCardBubble({
    super.key,
    required this.msg,
    required this.card,
    required this.isMine,
    required this.time,
    this.readCount = 0,
  });

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
            Avatar(
              name: msg.username,
              color: avatarColor(msg.username),
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
