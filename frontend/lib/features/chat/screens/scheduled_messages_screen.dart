import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../scheduled_messages_provider.dart';

class ScheduledMessagesScreen extends ConsumerWidget {
  const ScheduledMessagesScreen({super.key});

  String _format(DateTime dt) =>
      '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')} '
      '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(scheduledMessagesProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('예약된 메시지'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
            onPressed: () =>
                ref.read(scheduledMessagesProvider.notifier).refresh(),
          ),
        ],
      ),
      body: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('불러오기 실패: $e')),
        data: (items) {
          if (items.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  '예약된 메시지가 없습니다.\n메시지 입력 후 보내기 버튼을 길게 눌러 예약하세요.',
                  textAlign: TextAlign.center,
                ),
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () =>
                ref.read(scheduledMessagesProvider.notifier).refresh(),
            child: ListView.builder(
              itemCount: items.length,
              itemBuilder: (ctx, i) {
                final item = items[i];
                return ListTile(
                  leading: const Icon(Icons.schedule_send_outlined),
                  title: Text(
                    item.content,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    '${item.chatRoomId}  ·  ${_format(item.scheduledAtDateTime)}',
                  ),
                  trailing: IconButton(
                    icon: const Icon(Icons.cancel_outlined),
                    tooltip: '취소',
                    onPressed: () => _confirmCancel(ctx, ref, item.id),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  Future<void> _confirmCancel(
      BuildContext context, WidgetRef ref, int id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (dctx) => AlertDialog(
        title: const Text('예약 취소'),
        content: const Text('이 예약된 메시지를 취소하시겠습니까?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dctx).pop(false),
            child: const Text('아니오'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dctx).pop(true),
            child: const Text('취소'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(scheduledMessagesProvider.notifier).cancel(id);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('예약이 취소되었습니다.')),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('취소 실패: $e')),
        );
      }
    }
  }
}
