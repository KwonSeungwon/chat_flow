import 'package:flutter/material.dart';

Widget buildDragDropZone(
  BuildContext context, {
  required Widget child,
  required Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped,
}) {
  // Non-web: pass through unchanged.
  return child;
}
