import 'dart:convert';
import 'package:flutter/material.dart';

class SbarInputDialog extends StatefulWidget {
  final void Function(String content) onSend;
  const SbarInputDialog({super.key, required this.onSend});

  @override
  State<SbarInputDialog> createState() => _SbarInputDialogState();
}

class _SbarInputDialogState extends State<SbarInputDialog> {
  final _sCtrl = TextEditingController();
  final _bCtrl = TextEditingController();
  final _aCtrl = TextEditingController();
  final _rCtrl = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.assignment_outlined, size: 20),
          SizedBox(width: 8),
          Text('SBAR 인수인계'),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _SbarField(label: 'S - Situation (상황)', controller: _sCtrl, hint: '환자의 현재 상황을 설명하세요'),
            const SizedBox(height: 12),
            _SbarField(label: 'B - Background (배경)', controller: _bCtrl, hint: '관련 병력, 검사 결과 등'),
            const SizedBox(height: 12),
            _SbarField(label: 'A - Assessment (평가)', controller: _aCtrl, hint: '현재 상태에 대한 평가'),
            const SizedBox(height: 12),
            _SbarField(label: 'R - Recommendation (권고)', controller: _rCtrl, hint: '권고 사항 및 요청'),
          ],
        ),
      ),
      actionsAlignment: MainAxisAlignment.center,
      actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
      actions: [
        Row(children: [
          Expanded(child: TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('취소'),
          )),
          const SizedBox(width: 8),
          Expanded(child: FilledButton(
            onPressed: () {
              if (_sCtrl.text.trim().isEmpty) return;
              final sbar = {
                'type': 'SBAR',
                'situation': _sCtrl.text.trim(),
                'background': _bCtrl.text.trim(),
                'assessment': _aCtrl.text.trim(),
                'recommendation': _rCtrl.text.trim(),
              };
              widget.onSend(jsonEncode(sbar));
              Navigator.of(context).pop();
            },
            child: const Text('전송'),
          )),
        ]),
      ],
    );
  }
}

class _SbarField extends StatelessWidget {
  final String label;
  final TextEditingController controller;
  final String hint;

  const _SbarField({required this.label, required this.controller, required this.hint});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
        const SizedBox(height: 4),
        TextField(
          controller: controller,
          maxLines: 3,
          minLines: 2,
          decoration: InputDecoration(
            hintText: hint,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
            contentPadding: const EdgeInsets.all(10),
          ),
        ),
      ],
    );
  }
}
