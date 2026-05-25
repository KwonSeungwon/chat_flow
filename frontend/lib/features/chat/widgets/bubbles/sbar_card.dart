import 'package:flutter/material.dart';

class SbarCard extends StatelessWidget {
  final Map<String, dynamic> sbar;
  const SbarCard({super.key, required this.sbar});

  static const _sections = [
    ('S', 'Situation', Color(0xFF1976D2)),
    ('B', 'Background', Color(0xFF388E3C)),
    ('A', 'Assessment', Color(0xFFF57C00)),
    ('R', 'Recommendation', Color(0xFFD32F2F)),
  ];

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    final cardMaxWidth = screenWidth < 600
        ? (screenWidth * 0.70).clamp(180.0, 320.0)
        : 320.0;
    return Container(
      constraints: BoxConstraints(maxWidth: cardMaxWidth),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Theme.of(context).colorScheme.outline.withAlpha(80)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primaryContainer.withAlpha(80),
              borderRadius: const BorderRadius.vertical(top: Radius.circular(11)),
            ),
            child: const Row(
              children: [
                Icon(Icons.assignment_outlined, size: 16),
                SizedBox(width: 6),
                Flexible(child: Text('SBAR 인수인계', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13))),
              ],
            ),
          ),
          for (final sec in _sections)
            if ((sbar[sec.$1.toLowerCase() == 's' ? 'situation' : sec.$1.toLowerCase() == 'b' ? 'background' : sec.$1.toLowerCase() == 'a' ? 'assessment' : 'recommendation'] ?? '').toString().isNotEmpty)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  border: Border(left: BorderSide(color: sec.$3, width: 3)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${sec.$1} - ${sec.$2}', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: sec.$3)),
                    const SizedBox(height: 2),
                    Text(
                      sbar[sec.$1.toLowerCase() == 's' ? 'situation' : sec.$1.toLowerCase() == 'b' ? 'background' : sec.$1.toLowerCase() == 'a' ? 'assessment' : 'recommendation']?.toString() ?? '',
                      style: const TextStyle(fontSize: 13, height: 1.4),
                    ),
                  ],
                ),
              ),
          const SizedBox(height: 6),
        ],
      ),
    );
  }
}
