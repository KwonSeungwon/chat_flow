import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

/// PDF 뷰어 — 브라우저 내장 PDF 뷰어로 열기
class PdfViewerDialog {
  PdfViewerDialog._();

  static Future<void> open(BuildContext context, String url, String fileName) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.platformDefault);
    } else if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('PDF를 열 수 없습니다.')),
      );
    }
  }
}
