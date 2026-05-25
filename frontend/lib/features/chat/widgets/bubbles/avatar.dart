import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';

/// Deterministic avatar colour derived from a username.
Color avatarColor(String name) =>
    AppColors.avatarPalette[name.hashCode.abs() % AppColors.avatarPalette.length];

/// Gradient circle avatar showing the first letter of [name].
class Avatar extends StatelessWidget {
  final String name;
  final Color color;
  const Avatar({super.key, required this.name, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 30,
      height: 30,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [color.withAlpha(220), color.withAlpha(140)],
        ),
      ),
      child: Center(
        child: Text(
          name.isNotEmpty ? name[0].toUpperCase() : '?',
          style: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}
