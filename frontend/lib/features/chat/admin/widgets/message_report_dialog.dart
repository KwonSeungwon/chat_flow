import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/models/message_report.dart';
import '../room_admin_api_provider.dart';

Future<void> showMessageReportDialog(BuildContext context, String messageId) {
  return showDialog(
    context: context,
    builder: (_) => _MessageReportDialog(messageId: messageId),
  );
}

class _MessageReportDialog extends ConsumerStatefulWidget {
  final String messageId;
  const _MessageReportDialog({required this.messageId});

  @override
  ConsumerState<_MessageReportDialog> createState() => _MessageReportDialogState();
}

class _MessageReportDialogState extends ConsumerState<_MessageReportDialog> {
  ReportReason _reason = ReportReason.spam;
  final _commentCtrl = TextEditingController();
  bool _submitting = false;

  @override
  void dispose() {
    _commentCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('메시지 신고'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('사유를 선택해주세요.', style: TextStyle(fontSize: 13)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: ReportReason.values.map((r) {
                return ChoiceChip(
                  label: Text(_reasonLabel(r)),
                  selected: _reason == r,
                  onSelected: (sel) {
                    if (sel) setState(() => _reason = r);
                  },
                );
              }).toList(),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: _commentCtrl,
              maxLength: 500,
              maxLines: 3,
              decoration: const InputDecoration(
                hintText: '추가 설명 (선택)',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        TextButton(
          onPressed: _submitting ? null : _submit,
          child: _submitting
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('신고'),
        ),
      ],
    );
  }

  Future<void> _submit() async {
    setState(() => _submitting = true);
    try {
      final api = ref.read(roomAdminApiProvider);
      final comment = _commentCtrl.text.trim();
      await api.submitReport(widget.messageId, _reason, comment.isEmpty ? null : comment);
      if (mounted) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('신고가 접수되었습니다.'), duration: Duration(seconds: 2)),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _submitting = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('신고 실패: $e'), duration: const Duration(seconds: 3)),
        );
      }
    }
  }

  String _reasonLabel(ReportReason r) {
    switch (r) {
      case ReportReason.spam:
        return '스팸';
      case ReportReason.harassment:
        return '괴롭힘';
      case ReportReason.inappropriate:
        return '부적절한 콘텐츠';
      case ReportReason.other:
        return '기타';
    }
  }
}
