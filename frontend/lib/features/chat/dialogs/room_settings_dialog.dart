import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/constants/chat_strings.dart';
import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_room.dart';
import '../chat_provider.dart';

void showRoomSettingsDialog(BuildContext context, WidgetRef ref, String roomId, ChatRoom room) {
  final nameCtrl = TextEditingController(text: room.name);
  final descCtrl = TextEditingController(text: room.description ?? '');

  // 모바일(<600px)은 bottom sheet + 풀 스크롤, 데스크톱은 Dialog로 분기.
  final mq = MediaQuery.of(context);
  final isMobile = mq.size.width < 600;

  Future<void> save(BuildContext dialogCtx) async {
    try {
      await ref.read(dioClientProvider).dio.put('/api/chat/rooms/$roomId/settings', data: {
        'name': nameCtrl.text.trim(),
        'description': descCtrl.text.trim(),
      });
      if (dialogCtx.mounted) Navigator.of(dialogCtx).pop();
      ref.read(chatRoomsProvider.notifier).fetchRooms();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text(ChatStrings.roomSettingsChanged)));
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('설정 변경에 실패했습니다.')));
      }
    }
  }

  Widget buildBody(BuildContext dialogCtx) {
    return SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: nameCtrl,
            decoration: const InputDecoration(
              labelText: '채팅방 이름',
              border: OutlineInputBorder(),
              isDense: true,
            ),
            textInputAction: TextInputAction.next,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: descCtrl,
            decoration: const InputDecoration(
              labelText: '설명',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
            ),
            maxLines: 3,
            minLines: 2,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: TextButton(
                onPressed: () => Navigator.of(dialogCtx).pop(),
                child: const Text('취소'),
              )),
              const SizedBox(width: 8),
              Expanded(child: FilledButton(
                onPressed: () => save(dialogCtx),
                child: const Text('저장'),
              )),
            ],
          ),
        ],
      ),
    );
  }

  if (isMobile) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 4,
          bottom: MediaQuery.of(ctx).viewInsets.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.only(bottom: 12),
              child: Text('채팅방 설정',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            ),
            buildBody(ctx),
          ],
        ),
      ),
    );
  } else {
    showDialog(
      context: context,
      builder: (ctx) => Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Padding(
                  padding: EdgeInsets.only(bottom: 16),
                  child: Text('채팅방 설정',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                ),
                buildBody(ctx),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
