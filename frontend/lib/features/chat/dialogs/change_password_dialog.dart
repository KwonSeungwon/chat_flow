import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/constants/chat_strings.dart';
import '../../auth/auth_provider.dart';

class ChangePasswordDialog extends ConsumerStatefulWidget {
  const ChangePasswordDialog({super.key});

  @override
  ConsumerState<ChangePasswordDialog> createState() =>
      _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends ConsumerState<ChangePasswordDialog> {
  final _currentCtrl = TextEditingController();
  final _newCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  String? _error;
  bool _loading = false;

  @override
  void dispose() {
    _currentCtrl.dispose();
    _newCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_newCtrl.text != _confirmCtrl.text) {
      setState(() => _error = ChatStrings.passwordMismatch);
      return;
    }
    if (_newCtrl.text.length < 8) {
      setState(() => _error = ChatStrings.passwordTooShort);
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(authProvider.notifier)
          .changePassword(_currentCtrl.text, _newCtrl.text);
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text(ChatStrings.passwordChanged)),
      );
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = ChatStrings.passwordChangeFailedHint;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('비밀번호 변경'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('current_pw'),
            controller: _currentCtrl,
            obscureText: true,
            decoration: const InputDecoration(labelText: '현재 비밀번호'),
          ),
          const SizedBox(height: 8),
          TextField(
            key: const Key('new_pw'),
            controller: _newCtrl,
            obscureText: true,
            decoration:
                const InputDecoration(labelText: '새 비밀번호 (8자 이상)'),
          ),
          const SizedBox(height: 8),
          TextField(
            key: const Key('confirm_pw'),
            controller: _confirmCtrl,
            obscureText: true,
            decoration: const InputDecoration(labelText: '새 비밀번호 확인'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!,
                style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
        ],
      ),
      actionsAlignment: MainAxisAlignment.center,
      actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
      actions: [
        Row(children: [
          Expanded(
            child: TextButton(
              onPressed: _loading ? null : () => Navigator.of(context).pop(),
              child: const Text(ChatStrings.cancel),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: FilledButton(
              onPressed: _loading ? null : _submit,
              child: _loading
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white),
                    )
                  : const Text('변경'),
            ),
          ),
        ]),
      ],
    );
  }
}
