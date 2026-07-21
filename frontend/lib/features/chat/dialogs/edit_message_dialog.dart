import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../chat_provider.dart';

void showEditMessageDialog(
  BuildContext context,
  WidgetRef ref,
  String roomId,
  String messageId,
  String currentContent,
) {
  final ctrl = TextEditingController(text: currentContent);
  showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('메시지 수정'),
      content: TextField(
        controller: ctrl,
        maxLines: 5,
        minLines: 1,
        autofocus: true,
        decoration: const InputDecoration(
          hintText: '수정할 내용을 입력하세요',
          border: OutlineInputBorder(),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: () async {
            final newContent = ctrl.text.trim();
            if (newContent.isEmpty) return;
            Navigator.of(ctx).pop();
            final ok = await ref
                .read(chatNotifierProvider(roomId).notifier)
                .editMessage(roomId, messageId, newContent);
            if (!ok && context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('메시지 수정에 실패했습니다.')),
              );
            }
          },
          child: const Text('수정'),
        ),
      ],
    ),
  ).whenComplete(ctrl.dispose);
}
