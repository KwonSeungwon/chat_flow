import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../shared/models/chat_message.dart';
import '../chat_provider.dart';

void showForwardDialog(BuildContext context, WidgetRef ref, ChatNotifier currentNotifier, ChatMessage msg) {
  final rooms = ref.read(chatRoomsProvider).valueOrNull ?? [];
  showDialog(
    context: context,
    builder: (ctx) {
      String filter = '';
      return StatefulBuilder(
        builder: (ctx, setState) {
          final filtered = filter.isEmpty
              ? rooms
              : rooms.where((r) => r.name.toLowerCase().contains(filter.toLowerCase())).toList();
          final fwdMq = MediaQuery.of(ctx);
          final fwdMobile = fwdMq.size.width < 600;
          return AlertDialog(
            title: const Text('메시지 전달'),
            content: SizedBox(
              width: fwdMobile ? math.min(fwdMq.size.width - 64, 360.0) : 280,
              height: math.min(360.0, fwdMq.size.height - 200),
              child: Column(
                children: [
                  TextField(
                    decoration: const InputDecoration(
                      hintText: '방 검색',
                      prefixIcon: Icon(Icons.search, size: 20),
                      isDense: true,
                    ),
                    onChanged: (v) => setState(() => filter = v),
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: filtered.isEmpty
                        ? const Center(child: Text('일치하는 방이 없습니다.'))
                        : ListView.builder(
                            itemCount: filtered.length,
                            itemBuilder: (_, i) {
                              final room = filtered[i];
                              return ListTile(
                                leading: CircleAvatar(radius: 16, child: Text(room.name.isNotEmpty ? room.name[0].toUpperCase() : '#')),
                                title: Text(room.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                                onTap: () async {
                                  final messenger = ScaffoldMessenger.of(context);
                                  Navigator.of(ctx).pop();
                                  final ok = await currentNotifier.forwardMessage(room.id, msg);
                                  messenger.showSnackBar(SnackBar(
                                    content: Text(ok
                                        ? '"${room.name}"에 메시지를 전달했습니다.'
                                        : '연결 상태를 확인하고 다시 시도해주세요.'),
                                  ));
                                },
                              );
                            },
                          ),
                  ),
                ],
              ),
            ),
          );
        },
      );
    },
  );
}
