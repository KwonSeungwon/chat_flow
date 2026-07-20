import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/api_response.dart';
import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_message.dart';

void showReadersSheet(BuildContext context, WidgetRef ref, String roomId, String messageId, List<ChatMessage> messages) async {
  try {
    final resp = await ref.read(dioClientProvider).dio.get('/api/chat/rooms/$roomId/readers');
    // positions: {userId: lastReadMessageId}
    final unwrapped = apiResponseMap(resp.data);
    final positions = unwrapped != null
        ? Map<String, String>.from(unwrapped)
        : <String, String>{};

    // Find the index of target message to compare read positions
    final targetIdx = messages.indexWhere((m) => m.effectiveId == messageId);
    if (targetIdx < 0) return;

    // Users who have read at or past the target message
    final readers = <String>[];
    for (final entry in positions.entries) {
      final readerLastReadId = entry.value;
      final readerIdx = messages.indexWhere((m) => m.effectiveId == readerLastReadId);
      if (readerIdx >= targetIdx) {
        // Find username from messages sent by this userId
        String? username;
        for (final m in messages) {
          if (m.userId == entry.key) { username = m.username; break; }
        }
        readers.add(username ?? entry.key);
      }
    }

    if (!context.mounted) return;
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            Container(width: 36, height: 4, decoration: BoxDecoration(color: Colors.grey.withAlpha(80), borderRadius: BorderRadius.circular(2))),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text('읽은 사람 (${readers.length}명)', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
            ),
            if (readers.isEmpty)
              const Padding(padding: EdgeInsets.all(16), child: Text('읽은 사용자가 없습니다.'))
            else
              ...readers.map((name) => ListTile(
                leading: CircleAvatar(radius: 16, child: Text(name.isNotEmpty ? name[0].toUpperCase() : '?', style: const TextStyle(fontSize: 14))),
                title: Text(name),
              )),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  } catch (_) {}
}
