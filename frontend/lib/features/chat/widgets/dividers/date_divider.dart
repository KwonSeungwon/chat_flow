import 'package:flutter/material.dart';

class DateDivider extends StatelessWidget {
  final String date;
  const DateDivider({super.key, required this.date});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(child: Divider(color: cs.outline.withAlpha(60), height: 1)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: cs.surfaceContainer,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: cs.outline.withAlpha(60)),
              ),
              child: Text(
                date,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w500,
                  color: cs.onSurfaceVariant.withAlpha(180),
                ),
              ),
            ),
          ),
          Expanded(child: Divider(color: cs.outline.withAlpha(60), height: 1)),
        ],
      ),
    );
  }
}
