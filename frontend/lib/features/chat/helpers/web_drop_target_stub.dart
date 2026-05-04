import 'package:flutter/widgets.dart';

import 'pasted_image.dart';

/// Non-web stub: drag-drop is not supported on native platforms.
/// Simply renders [child] as-is.
class WebDropTarget extends StatelessWidget {
  final Widget child;
  final void Function(PastedImage image)? onImageDrop;
  final ValueChanged<bool>? onHoverChanged;

  const WebDropTarget({
    super.key,
    required this.child,
    this.onImageDrop,
    this.onHoverChanged,
  });

  @override
  Widget build(BuildContext context) => child;
}
