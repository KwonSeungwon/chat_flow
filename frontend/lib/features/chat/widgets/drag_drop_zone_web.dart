import 'dart:async';
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;

import 'package:flutter/material.dart';

Widget buildDragDropZone(
  BuildContext context, {
  required Widget child,
  required Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped,
}) {
  return _WebDragDropZone(onFileDropped: onFileDropped, child: child);
}

class _WebDragDropZone extends StatefulWidget {
  final Widget child;
  final Future<void> Function(String fileName, List<int> bytes, String mimeType) onFileDropped;

  const _WebDragDropZone({required this.child, required this.onFileDropped});

  @override
  State<_WebDragDropZone> createState() => _WebDragDropZoneState();
}

class _WebDragDropZoneState extends State<_WebDragDropZone> {
  bool _isDragging = false;
  late final StreamSubscription<html.MouseEvent> _enterSub;
  late final StreamSubscription<html.MouseEvent> _overSub;
  late final StreamSubscription<html.MouseEvent> _leaveSub;
  late final StreamSubscription<html.MouseEvent> _dropSub;

  static const _maxBytes = 50 * 1024 * 1024;

  @override
  void initState() {
    super.initState();
    final body = html.document.body!;
    _enterSub = body.onDragEnter.listen(_onEnter);
    _overSub = body.onDragOver.listen(_onOver);
    _leaveSub = body.onDragLeave.listen(_onLeave);
    _dropSub = body.onDrop.listen(_onDrop);
  }

  void _onEnter(html.MouseEvent event) {
    event.preventDefault();
    if (!_isDragging && mounted) setState(() => _isDragging = true);
  }

  void _onOver(html.MouseEvent event) {
    event.preventDefault();
  }

  void _onLeave(html.MouseEvent event) {
    event.preventDefault();
    // The leave event fires for child elements too — only flip off when the
    // pointer actually exits the window.
    if (event.client.x <= 0 ||
        event.client.y <= 0 ||
        event.client.x >= html.window.innerWidth! ||
        event.client.y >= html.window.innerHeight!) {
      if (mounted) setState(() => _isDragging = false);
    }
  }

  Future<void> _onDrop(html.MouseEvent event) async {
    event.preventDefault();
    if (mounted) setState(() => _isDragging = false);

    // dart:html dispatches drop as MouseEvent (no DragEvent class is exposed),
    // but the underlying event still carries dataTransfer.
    final files = event.dataTransfer.files;
    if (files == null || files.isEmpty) return;

    final file = files.first;
    if (file.size > _maxBytes) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('파일 크기가 너무 큽니다 (최대 50MB).')),
      );
      return;
    }

    final reader = html.FileReader();
    reader.readAsArrayBuffer(file);
    await reader.onLoad.first;
    final bytes = (reader.result as List<int>);
    final mime = file.type.isEmpty ? 'application/octet-stream' : file.type;
    await widget.onFileDropped(file.name, bytes, mime);
  }

  @override
  void dispose() {
    _enterSub.cancel();
    _overSub.cancel();
    _leaveSub.cancel();
    _dropSub.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        widget.child,
        if (_isDragging)
          Positioned.fill(
            child: IgnorePointer(
              child: Container(
                color: Theme.of(context).colorScheme.primary.withAlpha(40),
                alignment: Alignment.center,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surface,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: Theme.of(context).colorScheme.primary,
                      width: 2,
                      style: BorderStyle.solid,
                    ),
                  ),
                  child: Text(
                    '📎  파일을 여기에 드롭하세요',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
