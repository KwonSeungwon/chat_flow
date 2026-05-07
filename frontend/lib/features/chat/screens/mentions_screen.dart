import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../mentions_provider.dart';

class MentionsScreen extends ConsumerWidget {
  const MentionsScreen({super.key});

  String _format(DateTime dt) {
    final diff = DateTime.now().difference(dt);
    if (diff.inMinutes < 1) return '방금';
    if (diff.inHours < 1) return '${diff.inMinutes}분 전';
    if (diff.inDays < 1) return '${diff.inHours}시간 전';
    if (diff.inDays < 7) return '${diff.inDays}일 전';
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(mentionsProvider);
    final items = state.items;

    return Scaffold(
      appBar: AppBar(
        title: const Text('내 멘션'),
        actions: [
          IconButton(
            icon: const Icon(Icons.done_all),
            tooltip: '모두 읽음',
            onPressed: () =>
                ref.read(mentionsProvider.notifier).markAllRead(),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
            onPressed: () => ref.read(mentionsProvider.notifier).refresh(),
          ),
        ],
      ),
      body: items.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('불러오기 실패: $e')),
        data: (list) {
          if (list.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  '최근 30일간 멘션이 없습니다.',
                  textAlign: TextAlign.center,
                ),
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () => ref.read(mentionsProvider.notifier).refresh(),
            child: ListView.builder(
              itemCount: list.length,
              itemBuilder: (ctx, i) {
                final m = list[i];
                final cs = Theme.of(ctx).colorScheme;
                return ListTile(
                  leading: CircleAvatar(
                    backgroundColor:
                        m.read ? cs.surfaceContainer : cs.primary,
                    child: Text(
                      m.fromUsername.isNotEmpty
                          ? m.fromUsername[0].toUpperCase()
                          : '?',
                      style: TextStyle(
                          color: m.read ? cs.onSurfaceVariant : Colors.white),
                    ),
                  ),
                  title: Text(
                    '${m.fromUsername} → ${m.chatRoomId}',
                    style: TextStyle(
                        fontWeight:
                            m.read ? FontWeight.normal : FontWeight.bold),
                  ),
                  subtitle: Text(m.contentPreview,
                      maxLines: 2, overflow: TextOverflow.ellipsis),
                  trailing: Text(_format(m.when),
                      style: const TextStyle(fontSize: 12)),
                  onTap: () async {
                    await ref
                        .read(mentionsProvider.notifier)
                        .markRead(m.messageId);
                    if (!ctx.mounted) return;
                    ctx.go(
                        '/chat/${m.chatRoomId}?messageId=${m.messageId}');
                  },
                );
              },
            ),
          );
        },
      ),
    );
  }
}
