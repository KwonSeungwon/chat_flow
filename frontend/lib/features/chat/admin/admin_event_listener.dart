import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'admin_event_state.dart';

/// Listens to kickedEventProvider / mutedEventProvider and reacts:
/// - kicked/banned: SnackBar + router.go('/chat') (사이드바로 복귀)
/// - muted: SnackBar 만 — 입력창 비활성은 ChatInput 쪽에서 mutedEventProvider 직접 watch
/// 위젯 트리에 한 번만 위치시키면 됨 (chat_page Scaffold body의 최상단).
class AdminEventListener extends ConsumerWidget {
  final Widget child;
  final String? roomId;

  const AdminEventListener({super.key, required this.child, this.roomId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen<KickedEvent?>(kickedEventProvider, (prev, next) {
      if (next == null) return;
      final isBan = next.reason == 'BANNED';
      final msg = isBan
          ? '${next.by != null ? "${next.by} 님에 의해 " : ""}차단되었습니다.'
          : '${next.by != null ? "${next.by} 님에 의해 " : ""}강퇴되었습니다.';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(msg),
        duration: const Duration(seconds: 4),
        backgroundColor: isBan ? Colors.red.shade700 : Colors.orange.shade800,
      ));
      // 라우터 복귀 — 현재 화면이 강퇴된 방이면 사이드바로 푸시
      if (roomId == next.roomId) {
        context.go('/chat');
      }
      ref.read(kickedEventProvider.notifier).state = null;
    });

    if (roomId != null) {
      ref.listen<MutedEvent?>(mutedEventProvider(roomId!), (prev, next) {
        if (next == null) return;
        final hh = next.mutedUntil.hour.toString().padLeft(2, '0');
        final mm = next.mutedUntil.minute.toString().padLeft(2, '0');
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('음소거되었습니다. ($hh:$mm까지)'),
          duration: const Duration(seconds: 4),
          backgroundColor: Colors.orange.shade700,
        ));
        // mutedEvent는 ChatInput에서도 watch — clear 시점 분산 방지 위해 여기서는 clear 안 함
      });
    }

    return child;
  }
}
