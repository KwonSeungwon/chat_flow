import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../admin/widgets/room_members_sheet.dart';

class ParticipantBadge extends ConsumerWidget {
  final int count;
  final int max;
  final String roomId;

  const ParticipantBadge({
    super.key,
    required this.count,
    required this.max,
    required this.roomId,
  });

  void _showModal(BuildContext context, WidgetRef ref) {
    // 운영 도구 통합 멤버 시트로 일원화 — 역할 배지 + 강퇴/뮤트/위임/ban 액션 포함.
    showRoomMembersSheet(context, roomId);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colorScheme = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: () => _showModal(context, ref),
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: colorScheme.surfaceContainer,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: colorScheme.outline),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.people_outline_rounded,
                size: 13, color: colorScheme.onSurfaceVariant),
            const SizedBox(width: 4),
            Text(
              '$count/$max',
              style: TextStyle(
                  fontSize: 12, color: colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}
