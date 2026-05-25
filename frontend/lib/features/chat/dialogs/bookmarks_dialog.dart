import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../bookmark_provider.dart';

void showBookmarksDialog(BuildContext context, WidgetRef ref) {
  showDialog(
    context: context,
    builder: (ctx) {
      final mq = MediaQuery.of(ctx);
      return Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 500, maxHeight: 600),
          child: SizedBox(
            width: math.min(500.0, mq.size.width - 32),
            height: math.min(600.0, mq.size.height - 120),
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 16, 8, 8),
                  child: Row(
                    children: [
                      const Icon(Icons.bookmark, size: 20),
                      const SizedBox(width: 8),
                      const Text('북마크', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                      const Spacer(),
                      IconButton(
                        icon: const Icon(Icons.close, size: 20),
                        onPressed: () => Navigator.of(ctx).pop(),
                      ),
                    ],
                  ),
                ),
                const Divider(height: 1),
                Expanded(
                  child: Consumer(builder: (_, ref, __) {
                    final bookmarks = ref.watch(bookmarksProvider);
                    if (bookmarks.isEmpty) {
                      return Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.bookmark_border, size: 48, color: Theme.of(ctx).colorScheme.onSurfaceVariant.withAlpha(100)),
                            const SizedBox(height: 12),
                            Text('저장된 북마크가 없습니다.', style: TextStyle(color: Theme.of(ctx).colorScheme.onSurfaceVariant)),
                          ],
                        ),
                      );
                    }
                    return ListView.separated(
                      itemCount: bookmarks.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (_, i) {
                        final b = bookmarks[i];
                        String formattedTime = b.timestamp;
                        try {
                          final dt = DateTime.parse(b.timestamp).toLocal();
                          formattedTime = '${dt.month}/${dt.day} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
                        } catch (_) {}
                        return ListTile(
                          title: Text(b.content, maxLines: 2, overflow: TextOverflow.ellipsis),
                          subtitle: Text('${b.username} · $formattedTime', style: const TextStyle(fontSize: 11)),
                          trailing: IconButton(
                            icon: const Icon(Icons.delete_outline, size: 18),
                            onPressed: () => ref.read(bookmarksProvider.notifier).remove(b.messageId),
                          ),
                          onTap: () {
                            Navigator.of(ctx).pop();
                            GoRouter.of(context).go('/chat/${b.roomId}?messageId=${b.messageId}');
                          },
                        );
                      },
                    );
                  }),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}
