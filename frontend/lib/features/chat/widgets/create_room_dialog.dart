import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../chat_provider.dart';

class CreateRoomDialog extends ConsumerStatefulWidget {
  const CreateRoomDialog({super.key});

  @override
  ConsumerState<CreateRoomDialog> createState() => _CreateRoomDialogState();
}

class _CreateRoomDialogState extends ConsumerState<CreateRoomDialog> {
  final _nameCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  int _selectedColorIndex = 0;
  bool _isCreating = false;

  static const _presetColors = [
    '#1976D2', // Blue
    '#388E3C', // Green
    '#F57C00', // Orange
    '#7B1FA2', // Purple
    '#D32F2F', // Red
    '#00796B', // Teal
    '#303F9F', // Indigo
    '#C2185B', // Pink
    '#0097A7', // Cyan
    '#FFA000', // Amber
  ];

  Color _parseHex(String hex) {
    final cleaned = hex.replaceFirst('#', '');
    return Color(int.parse('FF$cleaned', radix: 16));
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _isCreating = true);
    try {
      await ref.read(chatRoomsProvider.notifier).createRoom(
        name: _nameCtrl.text.trim(),
        description:
            _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
        color: _presetColors[_selectedColorIndex],
      );
      if (mounted) Navigator.of(context).pop();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('채팅방 생성에 실패했습니다')),
        );
      }
    } finally {
      if (mounted) setState(() => _isCreating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return AlertDialog(
      title: const Text('새 채팅방'),
      content: SizedBox(
        width: 360,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextFormField(
                controller: _nameCtrl,
                decoration: const InputDecoration(
                  labelText: '채팅방 이름',
                  hintText: '2~50자',
                  prefixIcon: Icon(Icons.tag),
                ),
                maxLength: 50,
                autofocus: true,
                validator: (v) {
                  if (v == null || v.trim().length < 2) {
                    return '이름은 2자 이상이어야 합니다';
                  }
                  if (v.trim().length > 50) {
                    return '이름은 50자 이하여야 합니다';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _descCtrl,
                decoration: const InputDecoration(
                  labelText: '설명 (선택)',
                  hintText: '최대 200자',
                  prefixIcon: Icon(Icons.description_outlined),
                ),
                maxLength: 200,
                maxLines: 2,
                validator: (v) {
                  if (v != null && v.length > 200) {
                    return '설명은 200자 이하여야 합니다';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),
              Text(
                '색상',
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: List.generate(_presetColors.length, (i) {
                  final color = _parseHex(_presetColors[i]);
                  final selected = i == _selectedColorIndex;
                  return GestureDetector(
                    onTap: () => setState(() => _selectedColorIndex = i),
                    child: Container(
                      width: 32,
                      height: 32,
                      decoration: BoxDecoration(
                        color: color,
                        shape: BoxShape.circle,
                        border: Border.all(
                          color:
                              selected
                                  ? colorScheme.onSurface
                                  : Colors.transparent,
                          width: 2.5,
                        ),
                      ),
                      child:
                          selected
                              ? const Icon(
                                Icons.check,
                                size: 16,
                                color: Colors.white,
                              )
                              : null,
                    ),
                  );
                }),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isCreating ? null : () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: _isCreating ? null : _create,
          child:
              _isCreating
                  ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                  : const Text('생성'),
        ),
      ],
    );
  }
}
