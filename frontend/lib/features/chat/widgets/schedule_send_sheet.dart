import 'package:flutter/material.dart';

/// A modal bottom sheet that lets the user pick a future DateTime.
/// Returns null if dismissed without picking.
Future<DateTime?> showScheduleSendSheet(BuildContext context) async {
  final now = DateTime.now();
  final today6pm = DateTime(now.year, now.month, now.day, 18, 0);
  final tomorrow9am = DateTime(now.year, now.month, now.day, 9, 0)
      .add(const Duration(days: 1));

  final presets = <_Preset>[
    _Preset('1시간 후', now.add(const Duration(hours: 1))),
    _Preset('오늘 저녁 6시', today6pm),
    _Preset('내일 오전 9시', tomorrow9am),
  ];
  final validPresets = presets.where((p) => p.when.isAfter(now)).toList();

  return showModalBottomSheet<DateTime>(
    context: context,
    isScrollControlled: true,
    builder: (ctx) {
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('예약 발송 시각',
                  style: Theme.of(ctx).textTheme.titleMedium),
              const SizedBox(height: 12),
              ...validPresets.map((p) => ListTile(
                    leading: const Icon(Icons.schedule),
                    title: Text(p.label),
                    subtitle: Text(_formatDateTime(p.when)),
                    onTap: () => Navigator.of(ctx).pop(p.when),
                  )),
              const Divider(),
              ListTile(
                leading: const Icon(Icons.event),
                title: const Text('직접 선택...'),
                onTap: () async {
                  final picked = await _pickCustom(ctx);
                  if (picked != null && ctx.mounted) {
                    Navigator.of(ctx).pop(picked);
                  }
                },
              ),
              const SizedBox(height: 8),
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('취소'),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _Preset {
  final String label;
  final DateTime when;
  _Preset(this.label, this.when);
}

String _formatDateTime(DateTime dt) =>
    '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')} '
    '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';

Future<DateTime?> _pickCustom(BuildContext context) async {
  final now = DateTime.now();
  final date = await showDatePicker(
    context: context,
    firstDate: now,
    lastDate: now.add(const Duration(days: 365)),
    initialDate: now.add(const Duration(hours: 1)),
  );
  if (date == null) return null;
  if (!context.mounted) return null;
  final initialTime = TimeOfDay.now()
      .replacing(minute: (TimeOfDay.now().minute ~/ 5) * 5);
  final time = await showTimePicker(
    context: context,
    initialTime: initialTime,
  );
  if (time == null) return null;
  final picked = DateTime(date.year, date.month, date.day, time.hour, time.minute);
  if (!picked.isAfter(now)) return null;
  return picked;
}
