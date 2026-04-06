import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../shared/models/patient_card.dart';

class PatientCardInputDialog extends StatefulWidget {
  const PatientCardInputDialog({super.key});

  @override
  State<PatientCardInputDialog> createState() => _PatientCardInputDialogState();
}

class _PatientCardInputDialogState extends State<PatientCardInputDialog> {
  final _formKey        = GlobalKey<FormState>();
  final _nameCtrl       = TextEditingController();
  final _roomCtrl       = TextEditingController();
  final _diagnosisCtrl  = TextEditingController();
  final _allergiesCtrl  = TextEditingController();
  final _bpCtrl         = TextEditingController();
  final _hrCtrl         = TextEditingController();
  final _rrCtrl         = TextEditingController();
  final _btCtrl         = TextEditingController();
  final _spo2Ctrl       = TextEditingController();
  final _notesCtrl      = TextEditingController();

  @override
  void dispose() {
    _nameCtrl.dispose();
    _roomCtrl.dispose();
    _diagnosisCtrl.dispose();
    _allergiesCtrl.dispose();
    _bpCtrl.dispose();
    _hrCtrl.dispose();
    _rrCtrl.dispose();
    _btCtrl.dispose();
    _spo2Ctrl.dispose();
    _notesCtrl.dispose();
    super.dispose();
  }

  void _submit() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final card = PatientCard(
      patientName:     _nameCtrl.text.trim(),
      roomNumber:      _roomCtrl.text.trim(),
      diagnosis:       _diagnosisCtrl.text.trim(),
      allergies:       _allergiesCtrl.text.trim().isEmpty ? null : _allergiesCtrl.text.trim(),
      bloodPressure:   _bpCtrl.text.trim().isEmpty ? null : _bpCtrl.text.trim(),
      heartRate:       int.tryParse(_hrCtrl.text.trim()),
      respiratoryRate: int.tryParse(_rrCtrl.text.trim()),
      bodyTemperature: double.tryParse(_btCtrl.text.trim()),
      spO2:            int.tryParse(_spo2Ctrl.text.trim()),
      notes:           _notesCtrl.text.trim().isEmpty ? null : _notesCtrl.text.trim(),
    );
    Navigator.of(context).pop(card);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return AlertDialog(
      title: Row(
        children: [
          Icon(Icons.person_add_outlined, size: 20, color: cs.primary),
          const SizedBox(width: 8),
          const Text('환자 카드 전송'),
        ],
      ),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: SingleChildScrollView(
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Required fields
                Row(
                  children: [
                    Expanded(
                      flex: 2,
                      child: TextFormField(
                        controller: _nameCtrl,
                        decoration: const InputDecoration(
                          labelText: '환자명 *',
                          hintText: '홍길동',
                          prefixIcon: Icon(Icons.person_outline, size: 18),
                        ),
                        textInputAction: TextInputAction.next,
                        validator: (v) {
                          if (v == null || v.trim().isEmpty) return '필수 항목';
                          return null;
                        },
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextFormField(
                        controller: _roomCtrl,
                        decoration: const InputDecoration(
                          labelText: '병실 *',
                          hintText: '301',
                          prefixIcon: Icon(Icons.bed_outlined, size: 18),
                        ),
                        textInputAction: TextInputAction.next,
                        validator: (v) {
                          if (v == null || v.trim().isEmpty) return '필수';
                          return null;
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                TextFormField(
                  controller: _diagnosisCtrl,
                  decoration: const InputDecoration(
                    labelText: '진단명 *',
                    hintText: '예: 폐렴, 고혈압',
                    prefixIcon: Icon(Icons.medical_information_outlined, size: 18),
                  ),
                  textInputAction: TextInputAction.next,
                  validator: (v) {
                    if (v == null || v.trim().isEmpty) return '필수 항목';
                    return null;
                  },
                ),
                const SizedBox(height: 10),
                TextFormField(
                  controller: _allergiesCtrl,
                  decoration: const InputDecoration(
                    labelText: '알러지 (선택)',
                    hintText: '예: 페니실린, 조영제',
                    prefixIcon: Icon(Icons.warning_amber_outlined, size: 18),
                  ),
                  textInputAction: TextInputAction.next,
                ),
                const SizedBox(height: 14),

                // Vitals section
                Text(
                  '활력징후 (선택)',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: cs.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _bpCtrl,
                        decoration: const InputDecoration(
                          labelText: 'BP',
                          hintText: '120/80',
                        ),
                        textInputAction: TextInputAction.next,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextFormField(
                        controller: _hrCtrl,
                        decoration: const InputDecoration(
                          labelText: 'HR',
                          hintText: '72',
                        ),
                        keyboardType: TextInputType.number,
                        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                        textInputAction: TextInputAction.next,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextFormField(
                        controller: _rrCtrl,
                        decoration: const InputDecoration(
                          labelText: 'RR',
                          hintText: '18',
                        ),
                        keyboardType: TextInputType.number,
                        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                        textInputAction: TextInputAction.next,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _btCtrl,
                        decoration: const InputDecoration(
                          labelText: 'BT (°C)',
                          hintText: '36.5',
                        ),
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        inputFormatters: [
                          FilteringTextInputFormatter.allow(RegExp(r'[\d.]')),
                        ],
                        textInputAction: TextInputAction.next,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextFormField(
                        controller: _spo2Ctrl,
                        decoration: const InputDecoration(
                          labelText: 'SpO2 (%)',
                          hintText: '98',
                        ),
                        keyboardType: TextInputType.number,
                        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                        textInputAction: TextInputAction.next,
                      ),
                    ),
                    const Expanded(child: SizedBox()),
                  ],
                ),
                const SizedBox(height: 10),
                TextFormField(
                  controller: _notesCtrl,
                  decoration: const InputDecoration(
                    labelText: '특이사항 (선택)',
                    hintText: '다음 교대 시 주의사항 등',
                    prefixIcon: Icon(Icons.notes_rounded, size: 18),
                  ),
                  maxLines: 2,
                  textInputAction: TextInputAction.done,
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        FilledButton.icon(
          onPressed: _submit,
          icon: const Icon(Icons.send_rounded, size: 16),
          label: const Text('전송'),
        ),
      ],
    );
  }
}
