import 'dart:typed_data';

import 'package:flutter/widgets.dart';

import 'pasted_image.dart';

/// Non-web stub: drag-drop is not supported on native platforms.
/// Simply renders [child] as-is. The constructor mirrors the web impl
/// so callsites compile on Android/iOS without conditional params.
class WebDropTarget extends StatelessWidget {
  final Widget child;
  final void Function(PastedImage image)? onImageDrop;
  final Future<void> Function(String fileName, Uint8List bytes, String mimeType)? onFileDrop;
  final ValueChanged<bool>? onHoverChanged;

  const WebDropTarget({
    super.key,
    required this.child,
    this.onImageDrop,
    this.onFileDrop,
    this.onHoverChanged,
  });

  @override
  Widget build(BuildContext context) => child;
}
