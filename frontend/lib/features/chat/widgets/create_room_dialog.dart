import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../chat_provider.dart';

class CreateRoomDialog extends ConsumerStatefulWidget {
  const CreateRoomDialog({super.key});

  @override
  ConsumerState<CreateRoomDialog> createState() => _CreateRoomDialogState();
}

class _CreateRoomDialogState extends ConsumerState<CreateRoomDialog> {
  final _nameCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  int _selectedColorIndex = 0;
  bool _isCreating = false;
  bool _isPrivate = false;
  bool _isHandoff = false;
  final Set<String> _allowedRoles = {};

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
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    if (_isCreating) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _isCreating = true);
    try {
      final roomId = await ref.read(chatRoomsProvider.notifier).createRoom(
        name: _nameCtrl.text.trim(),
        description:
            _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
        color: _isHandoff ? '#00796B' : _presetColors[_selectedColorIndex],
        roomType: _isHandoff ? 'HANDOFF' : 'GENERAL',
        isPrivate: _isPrivate,
        password: _isPrivate ? _passwordCtrl.text.trim() : null,
        allowedRoles: _allowedRoles.isEmpty ? null : _allowedRoles.join(','),
      );
      if (mounted) {
        Navigator.of(context).pop();
        if (roomId != null) context.go('/chat/$roomId');
      }
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
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360),
        child: SingleChildScrollView(
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
              // Room type toggle
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: false, label: Text('일반 채팅'), icon: Icon(Icons.chat_bubble_outline, size: 18)),
                  ButtonSegment(value: true, label: Text('인수인계'), icon: Icon(Icons.swap_horiz_rounded, size: 18)),
                ],
                selected: {_isHandoff},
                onSelectionChanged: (v) => setState(() => _isHandoff = v.first),
                showSelectedIcon: false,
                style: ButtonStyle(
                  visualDensity: VisualDensity.compact,
                  side: WidgetStatePropertyAll(BorderSide(color: colorScheme.outline)),
                  backgroundColor: WidgetStateProperty.resolveWith((states) {
                    if (states.contains(WidgetState.selected)) {
                      return _isHandoff ? const Color(0xFF00796B).withAlpha(50) : colorScheme.primaryContainer;
                    }
                    return colorScheme.surfaceContainerHigh;
                  }),
                  foregroundColor: WidgetStateProperty.resolveWith((states) {
                    if (states.contains(WidgetState.selected)) {
                      return _isHandoff ? const Color(0xFF00796B) : colorScheme.onPrimaryContainer;
                    }
                    return colorScheme.onSurface;
                  }),
                ),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _nameCtrl,
                decoration: InputDecoration(
                  labelText: _isHandoff ? '인수인계방 이름' : '채팅방 이름',
                  hintText: _isHandoff ? '예: 301호 김OO 인수인계' : '2~50자',
                  prefixIcon: Icon(_isHandoff ? Icons.swap_horiz_rounded : Icons.tag),
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
              const SizedBox(height: 12),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('비밀방'),
                subtitle: const Text('비밀번호를 입력해야 입장 가능'),
                secondary: Icon(_isPrivate ? Icons.lock : Icons.lock_open),
                value: _isPrivate,
                onChanged: (v) => setState(() => _isPrivate = v),
              ),
              if (_isPrivate) ...[
                const SizedBox(height: 8),
                TextFormField(
                  controller: _passwordCtrl,
                  decoration: const InputDecoration(
                    labelText: '비밀번호',
                    hintText: '4자 이상',
                    prefixIcon: Icon(Icons.password),
                  ),
                  obscureText: true,
                  validator: (v) {
                    if (_isPrivate && (v == null || v.trim().length < 4)) {
                      return '비밀번호는 4자 이상이어야 합니다';
                    }
                    return null;
                  },
                ),
              ],
              const SizedBox(height: 16),
              Text(
                '허용 역할 (선택 안 하면 전체 허용)',
                style: theme.textTheme.bodySmall?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [
                  for (final role in const ['DOCTOR', 'NURSE', 'PHARMACIST', 'ADMIN'])
                    FilterChip(
                      label: Text(
                        switch (role) {
                          'DOCTOR'     => '의사',
                          'NURSE'      => '간호사',
                          'PHARMACIST' => '약사',
                          _            => '관리자',
                        },
                        style: const TextStyle(fontSize: 12),
                      ),
                      selected: _allowedRoles.contains(role),
                      onSelected: (selected) {
                        setState(() {
                          if (selected) {
                            _allowedRoles.add(role);
                          } else {
                            _allowedRoles.remove(role);
                          }
                        });
                      },
                      visualDensity: VisualDensity.compact,
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                ],
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
      ),
      actions: [
        Expanded(
          child: TextButton(
            onPressed: _isCreating ? null : () => Navigator.of(context).pop(),
            child: const Text('취소'),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: FilledButton(
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
        ),
      ],
    );
  }
}
