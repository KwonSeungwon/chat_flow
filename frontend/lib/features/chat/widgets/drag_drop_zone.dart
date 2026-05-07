import 'package:flutter/material.dart';

import 'drag_drop_zone_stub.dart'
    if (dart.library.html) 'drag_drop_zone_web.dart' as impl;

/// Web-only drag-and-drop overlay. On non-web platforms this is a no-op
/// pass-through that just renders [child].
///
/// When a user drags a file over [child], an overlay appears. On drop,
/// the file is forwarded to [onFileDropped] which the chat page wires
/// into the existing upload pipeline.
class DragDropZone extends StatelessWidget {
  final Widget child;
  final Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped;

  const DragDropZone({
    super.key,
    required this.child,
    required this.onFileDropped,
  });

  @override
  Widget build(BuildContext context) =>
      impl.buildDragDropZone(context, child: child, onFileDropped: onFileDropped);
}
