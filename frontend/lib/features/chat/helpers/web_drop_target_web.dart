import 'dart:async';
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;
import 'dart:typed_data';

import 'package:flutter/widgets.dart';

import 'pasted_image.dart';

/// Web implementation: wraps [child] and listens to HTML5 drag/drop events
/// and paste events on the document body for image files.
///
/// IMPORTANT: This implementation listens on `document.body` and `document` (page-wide),
/// not on a scoped element. ChatFlow currently mounts at most one `ChatInput` at a time
/// (`/chat/:roomId` only), so a singleton-by-convention assumption is safe.
///
/// If a future feature mounts a second `ChatInput` simultaneously (e.g., reply-in-thread
/// modal, side-panel chat), every drop/paste will be received by ALL instances and the
/// "first-image-wins" guard is per-instance, not global — refactor this listener to
/// scope to a specific element or a class-level static before adding a second consumer.
class WebDropTarget extends StatefulWidget {
  final Widget child;

  /// Fires for clipboard paste of images (always).
  /// Also fires for drag-dropped images, but only when [onFileDrop] is null
  /// (backward compat for the existing ChatInput consumer).
  final void Function(PastedImage image)? onImageDrop;

  /// When set, ALL drag-dropped files (images and non-images) route here
  /// instead of [onImageDrop]. Clipboard paste continues to go to
  /// [onImageDrop] regardless. Use this for generic file uploads or
  /// chat-area-wide drop overlays that need to handle non-image files.
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
  State<WebDropTarget> createState() => _WebDropTargetState();
}

class _WebDropTargetState extends State<WebDropTarget> {
  final List<StreamSubscription<html.Event>> _subs = [];
  int _dragCounter = 0;

  @override
  void initState() {
    super.initState();
    final body = html.document.body;
    if (body == null) return;

    _subs.add(body.onDragEnter.listen(_onDragEnter));
    _subs.add(body.onDragOver.listen(_onDragOver));
    _subs.add(body.onDragLeave.listen(_onDragLeave));
    _subs.add(body.onDrop.listen(_onDrop));
    // Listen for paste events (Ctrl+V / Cmd+V with image in clipboard)
    _subs.add(html.document.onPaste.listen(_onPaste));

    // Reset drag state when window loses focus or browser fires dragend — these can
    // fire instead of a balancing dragleave when the user drops outside the viewport.
    _subs.add(html.window.onBlur.listen((_) => _resetHover()));
    _subs.add(html.document.onDragEnd.listen((_) => _resetHover()));
  }

  @override
  void dispose() {
    for (final sub in _subs) {
      sub.cancel();
    }
    _subs.clear();
    super.dispose();
  }

  void _resetHover() {
    _dragCounter = 0;
    widget.onHoverChanged?.call(false);
  }

  void _onDragEnter(html.MouseEvent event) {
    event.preventDefault();
    _dragCounter++;
    if (_dragCounter == 1) {
      widget.onHoverChanged?.call(true);
    }
  }

  void _onDragOver(html.MouseEvent event) {
    // Must preventDefault to allow drop
    event.preventDefault();
  }

  void _onDragLeave(html.MouseEvent event) {
    event.preventDefault();
    _dragCounter--;
    if (_dragCounter <= 0) {
      _dragCounter = 0;
      widget.onHoverChanged?.call(false);
    }
  }

  Future<void> _onDrop(html.MouseEvent event) async {
    event.preventDefault();
    event.stopPropagation();
    _dragCounter = 0;
    widget.onHoverChanged?.call(false);

    final dataTransfer = event.dataTransfer;
    final files = dataTransfer.files;
    if (files == null || files.isEmpty) return;

    final file = files.first;
    final mimeType = file.type;
    final bytes = await _readFileAsBytes(file);
    if (bytes == null || bytes.isEmpty) return;

    if (widget.onFileDrop != null) {
      await widget.onFileDrop!(
        file.name,
        bytes,
        mimeType.isEmpty ? 'application/octet-stream' : mimeType,
      );
      return;
    }
    // Backward-compat fall-through: drag-dropped images still hit onImageDrop
    // when the consumer hasn't opted into the generic onFileDrop route.
    if (widget.onImageDrop != null && mimeType.startsWith('image/')) {
      widget.onImageDrop!(PastedImage(
        name: file.name,
        bytes: bytes,
        mimeType: mimeType,
      ));
    }
  }

  Future<void> _onPaste(html.ClipboardEvent event) async {
    if (widget.onImageDrop == null) return;

    final clipboardData = event.clipboardData;
    if (clipboardData == null) return;

    final files = clipboardData.files;
    if (files == null || files.isEmpty) return;

    for (final file in files) {
      final mimeType = file.type;
      if (mimeType.startsWith('image/')) {
        // Prevent the browser from inserting the image as an <img> tag or
        // pasting garbled text into the Flutter text field.
        event.preventDefault();
        final bytes = await _readFileAsBytes(file);
        if (bytes != null && bytes.isNotEmpty) {
          final ext = _mimeToExt(mimeType);
          widget.onImageDrop!(PastedImage(
            name: 'clipboard_image.$ext',
            bytes: bytes,
            mimeType: mimeType,
          ));
          return; // Only handle the first image
        }
      }
    }
  }

  Future<Uint8List?> _readFileAsBytes(html.File file) async {
    final reader = html.FileReader();
    reader.readAsArrayBuffer(file);
    await reader.onLoadEnd.first;
    final result = reader.result;
    if (result is Uint8List) return result;
    if (result is ByteBuffer) return result.asUint8List();
    return null;
  }

  String _mimeToExt(String mime) {
    switch (mime) {
      case 'image/png':
        return 'png';
      case 'image/jpeg':
        return 'jpg';
      case 'image/gif':
        return 'gif';
      case 'image/webp':
        return 'webp';
      default:
        return 'png';
    }
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
