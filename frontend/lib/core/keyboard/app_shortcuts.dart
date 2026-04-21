import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../features/chat/chat_provider.dart';

/// Global keyboard shortcuts widget wrapping the app.
/// Ctrl+K / Cmd+K: global search, Ctrl+/ / Cmd+/: help, Ctrl+J / Cmd+J: previous room.
class AppShortcuts extends ConsumerWidget {
  final Widget child;
  const AppShortcuts({super.key, required this.child});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Shortcuts(
      shortcuts: <ShortcutActivator, Intent>{
        // Ctrl+K / Cmd+K : global search
        const SingleActivator(LogicalKeyboardKey.keyK, control: true): const _GlobalSearchIntent(),
        const SingleActivator(LogicalKeyboardKey.keyK, meta: true): const _GlobalSearchIntent(),
        // Ctrl+/ / Cmd+/ : shortcut help
        const SingleActivator(LogicalKeyboardKey.slash, control: true): const _HelpIntent(),
        const SingleActivator(LogicalKeyboardKey.slash, meta: true): const _HelpIntent(),
        // Ctrl+J / Cmd+J : previous room
        const SingleActivator(LogicalKeyboardKey.keyJ, control: true): const _PrevRoomIntent(),
        const SingleActivator(LogicalKeyboardKey.keyJ, meta: true): const _PrevRoomIntent(),
      },
      child: Actions(
        actions: <Type, Action<Intent>>{
          _GlobalSearchIntent: CallbackAction<_GlobalSearchIntent>(
            onInvoke: (_) {
              GoRouter.of(context).go('/search');
              return null;
            },
          ),
          _HelpIntent: CallbackAction<_HelpIntent>(
            onInvoke: (_) {
              _showShortcutHelp(context);
              return null;
            },
          ),
          _PrevRoomIntent: CallbackAction<_PrevRoomIntent>(
            onInvoke: (_) {
              _gotoPreviousRoom(context, ref);
              return null;
            },
          ),
        },
        child: Focus(autofocus: true, child: child),
      ),
    );
  }

  void _gotoPreviousRoom(BuildContext context, WidgetRef ref) {
    final rooms = ref.read(chatRoomsProvider).valueOrNull ?? [];
    if (rooms.isEmpty) return;
    final current = ref.read(activeRoomIdProvider);
    final currentIdx = current == null
        ? -1
        : rooms.indexWhere((r) => r.id == current);
    final nextIdx = (currentIdx <= 0) ? rooms.length - 1 : currentIdx - 1;
    GoRouter.of(context).go('/chat/${rooms[nextIdx].id}');
  }

  void _showShortcutHelp(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('키보드 단축키'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ShortcutRow(keys: 'Ctrl + K', desc: '전역 검색'),
            _ShortcutRow(keys: 'Ctrl + /', desc: '단축키 도움말'),
            _ShortcutRow(keys: 'Ctrl + J', desc: '이전 방'),
            _ShortcutRow(keys: 'Esc', desc: '대화상자 닫기'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('닫기'),
          ),
        ],
      ),
    );
  }
}

class _GlobalSearchIntent extends Intent {
  const _GlobalSearchIntent();
}

class _HelpIntent extends Intent {
  const _HelpIntent();
}

class _PrevRoomIntent extends Intent {
  const _PrevRoomIntent();
}

class _ShortcutRow extends StatelessWidget {
  final String keys;
  final String desc;
  const _ShortcutRow({required this.keys, required this.desc});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(4),
              border: Border.all(
                color: Theme.of(context).colorScheme.outline.withAlpha(80),
              ),
            ),
            child: Text(
              keys,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
          ),
          const SizedBox(width: 12),
          Text(desc),
        ],
      ),
    );
  }
}
