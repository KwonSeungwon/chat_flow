import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:pdfx/pdfx.dart';

class PdfViewerDialog extends StatefulWidget {
  final String url;
  final String fileName;

  const PdfViewerDialog({
    super.key,
    required this.url,
    required this.fileName,
  });

  @override
  State<PdfViewerDialog> createState() => _PdfViewerDialogState();
}

class _PdfViewerDialogState extends State<PdfViewerDialog> {
  PdfControllerPinch? _controller;
  int _totalPages = 0;
  int _currentPage = 1;
  bool _loading = true;
  bool _hasError = false;

  @override
  void initState() {
    super.initState();
    _loadPdf();
  }

  Future<void> _loadPdf() async {
    try {
      final resp = await Dio().get<List<int>>(
        widget.url,
        options: Options(responseType: ResponseType.bytes),
      );
      final bytes = Uint8List.fromList(resp.data!);
      final doc = await PdfDocument.openData(bytes);
      if (!mounted) return;
      setState(() {
        _controller = PdfControllerPinch(document: Future.value(doc));
        _totalPages = doc.pagesCount;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _hasError = true;
        _loading = false;
      });
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        title: Text(
          widget.fileName,
          style: const TextStyle(fontSize: 14),
          overflow: TextOverflow.ellipsis,
        ),
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => Navigator.of(context).pop(),
        ),
        actions: [
          if (_totalPages > 0)
            Center(
              child: Padding(
                padding: const EdgeInsets.only(right: 16),
                child: Text(
                  '$_currentPage / $_totalPages',
                  style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
                ),
              ),
            ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _hasError
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.error_outline, size: 48, color: cs.error),
                      const SizedBox(height: 12),
                      Text('PDF를 불러올 수 없습니다.',
                          style: TextStyle(color: cs.onSurfaceVariant)),
                    ],
                  ),
                )
              : PdfViewPinch(
                  controller: _controller!,
                  onPageChanged: (page) {
                    setState(() => _currentPage = page);
                  },
                  padding: 8,
                  scrollDirection: Axis.vertical,
                ),
    );
  }
}
