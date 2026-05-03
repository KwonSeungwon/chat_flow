import 'package:flutter/material.dart';

import '../../../../shared/models/room_role.dart';

class RoleBadge extends StatelessWidget {
  final RoomRole role;
  final double fontSize;

  const RoleBadge({
    super.key,
    required this.role,
    this.fontSize = 10,
  });

  @override
  Widget build(BuildContext context) {
    if (role == RoomRole.member) return const SizedBox.shrink();

    final isOwner = role == RoomRole.owner;
    final color = isOwner ? const Color(0xFFD4A017) : const Color(0xFF7C6FF7);
    final label = isOwner ? '방장' : '운영자';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withAlpha(28),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: color.withAlpha(110), width: 0.8),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: fontSize,
          color: color,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
