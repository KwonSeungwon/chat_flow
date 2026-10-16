import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';

/// 카카오톡형 날짜 알약 — 가운데에 반투명 알약 하나만 띄운다.
class DateDivider extends StatelessWidget {
  final String date;
  const DateDivider({super.key, required this.date});

  @override
  Widget build(BuildContext context) {
    final cp = ChatPalette.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(
            color: cp.pill,
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.calendar_today_rounded, size: 12, color: cp.onPill),
              const SizedBox(width: 6),
              Text(
                date,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  color: cp.onPill,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
