import 'package:flutter/material.dart';

/// Dashed-border overlay shown when a user drags an image over the chat input area.
/// Renders nothing ([SizedBox.shrink]) when [active] is false.
class DropZoneOverlay extends StatelessWidget {
  final bool active;

  const DropZoneOverlay({super.key, required this.active});

  @override
  Widget build(BuildContext context) {
    if (!active) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;

    return Positioned.fill(
      child: IgnorePointer(
        child: Container(
          decoration: BoxDecoration(
            color: cs.primary.withAlpha(25),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: cs.primary.withAlpha(160),
              width: 2,
              strokeAlign: BorderSide.strokeAlignInside,
            ),
          ),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.cloud_upload_outlined,
                  size: 36,
                  color: cs.primary,
                ),
                const SizedBox(height: 8),
                Text(
                  '여기에 놓아 업로드',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: cs.primary,
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
