import 'package:flutter/material.dart';

class HoverReactionBar extends StatelessWidget {
  final List<String> reactions;
  final void Function(String emoji) onReaction;

  const HoverReactionBar({super.key, required this.reactions, required this.onReaction});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [BoxShadow(color: Colors.black.withAlpha(30), blurRadius: 6, offset: const Offset(0, 2))],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: reactions.map((emoji) => GestureDetector(
          onTap: () => onReaction(emoji),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 3),
            child: Text(emoji, style: const TextStyle(fontSize: 18)),
          ),
        )).toList(),
      ),
    );
  }
}
