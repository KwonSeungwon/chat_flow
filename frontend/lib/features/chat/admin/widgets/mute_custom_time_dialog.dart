import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 분 단위 커스텀 뮤트 시간 입력. 1~1440 (24시간) 범위.
/// 취소 시 null 반환, 확인 시 분(int) 반환.
Future<int?> showMuteCustomTimeDialog(BuildContext context) {
  return showDialog<int>(
    context: context,
    builder: (_) => const _MuteCustomTimeDialog(),
  );
}

class _MuteCustomTimeDialog extends StatefulWidget {
  const _MuteCustomTimeDialog();

  @override
  State<_MuteCustomTimeDialog> createState() => _MuteCustomTimeDialogState();
}

class _MuteCustomTimeDialogState extends State<_MuteCustomTimeDialog> {
  final _ctrl = TextEditingController();
  String? _error;

  static const int minMinutes = 1;
  static const int maxMinutes = 1440;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _confirm() {
    final raw = _ctrl.text.trim();
    final n = int.tryParse(raw);
    if (n == null) {
      setState(() => _error = '숫자를 입력하세요.');
      return;
    }
    if (n < minMinutes || n > maxMinutes) {
      setState(() => _error = '$minMinutes~$maxMinutes분 범위로 입력하세요.');
      return;
    }
    Navigator.of(context).pop(n);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('음소거 시간 (분)'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: _ctrl,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            autofocus: true,
            decoration: InputDecoration(
              hintText: '예: 12',
              suffixText: '분',
              border: const OutlineInputBorder(),
              errorText: _error,
            ),
            onSubmitted: (_) => _confirm(),
          ),
          const SizedBox(height: 6),
          Text('1분 이상 ~ 1440분(24시간) 이하',
              style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: _confirm,
          child: const Text('확인'),
        ),
      ],
    );
  }
}
