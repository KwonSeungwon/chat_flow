import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/theme_provider.dart';
import '../auth/auth_provider.dart';
import 'chat_provider.dart';
import 'widgets/chat_room_sidebar.dart';
import 'widgets/chat_messages_list.dart';
import 'widgets/chat_input.dart';

class ChatPage extends ConsumerWidget {
  final String? roomId;

  const ChatPage({super.key, this.roomId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    final themeMode = ref.watch(themeModeProvider);
    final isWide = MediaQuery.of(context).size.width >= 600;
    final effectiveRoomId = roomId;

    final roomDisplayName = effectiveRoomId != null
        ? ref.watch(chatRoomsProvider).maybeWhen(
              data: (rooms) {
                final match = rooms.where((r) => r.id == effectiveRoomId);
                return match.isNotEmpty ? match.first.name : effectiveRoomId;
              },
              orElse: () => effectiveRoomId,
            )
        : 'ChatFlow';

    return Scaffold(
      appBar: AppBar(
        leading:
            isWide
                ? null
                : Builder(
                  builder: (ctx) => IconButton(
                    icon: const Icon(Icons.menu),
                    onPressed: () => Scaffold.of(ctx).openDrawer(),
                  ),
                ),
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(roomDisplayName),
            if (effectiveRoomId != null) ...[
              const SizedBox(width: 8),
              _ConnectionDot(
                connected: ref.watch(chatNotifierProvider(effectiveRoomId)).isConnected,
              ),
            ],
          ],
        ),
        actions: [
          IconButton(
            icon: Icon(
              themeMode == ThemeMode.dark
                  ? Icons.light_mode_outlined
                  : Icons.dark_mode_outlined,
            ),
            tooltip: themeMode == ThemeMode.dark ? '라이트 모드' : '다크 모드',
            onPressed: () {
              ref.read(themeModeProvider.notifier).state =
                  themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
            },
          ),
          IconButton(
            icon: const Icon(Icons.search),
            tooltip: '메시지 검색',
            onPressed: () => context.push('/search'),
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (value) async {
              if (value == 'logout') {
                await ref.read(authProvider.notifier).logout();
                if (context.mounted) context.go('/login');
              }
            },
            itemBuilder: (context) => [
              PopupMenuItem(
                enabled: false,
                child: Text(
                  auth.username.isNotEmpty ? auth.username : '사용자',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ),
              const PopupMenuDivider(),
              const PopupMenuItem(value: 'logout', child: Text('로그아웃')),
            ],
          ),
        ],
      ),
      drawer:
          isWide
              ? null
              : Drawer(
                child: SafeArea(
                  child: ChatRoomSidebar(
                    currentRoomId: effectiveRoomId ?? '',
                    onRoomSelected: () => Navigator.of(context).pop(),
                  ),
                ),
              ),
      body: Row(
        children: [
          if (isWide)
            ChatRoomSidebar(currentRoomId: effectiveRoomId ?? ''),
          if (isWide)
            const VerticalDivider(width: 1, thickness: 1),
          Expanded(
            child: effectiveRoomId != null
                ? _ChatRoomContent(
                    roomId: effectiveRoomId,
                    username: auth.username,
                  )
                : const _LobbyPlaceholder(),
          ),
        ],
      ),
      resizeToAvoidBottomInset: true,
    );
  }
}

// ---------------------------------------------------------------------------
// Active chat room content (messages + input)
// ---------------------------------------------------------------------------
class _ChatRoomContent extends ConsumerWidget {
  final String roomId;
  final String username;

  const _ChatRoomContent({required this.roomId, required this.username});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final chatState = ref.watch(chatNotifierProvider(roomId));
    final chatNotifier = ref.read(chatNotifierProvider(roomId).notifier);

    return Column(
      children: [
        if (chatState.isLoadingHistory) const LinearProgressIndicator(),
        Expanded(
          child: ChatMessagesList(
            messages: chatState.messages,
            currentUsername: username,
          ),
        ),
        ChatInput(
          isConnected: chatState.isConnected,
          onSend: (content) {
            chatNotifier.sendMessage(roomId: roomId, content: content);
          },
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Lobby placeholder — shown when no room is selected
// ---------------------------------------------------------------------------
class _LobbyPlaceholder extends StatelessWidget {
  const _LobbyPlaceholder();

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.chat_bubble_outline, size: 64, color: colorScheme.outline),
          const SizedBox(height: 16),
          Text(
            '채팅방을 선택하거나 새로 만드세요',
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
          ),
          const SizedBox(height: 8),
          Text(
            '왼쪽 사이드바에서 + 버튼으로 채팅방을 만들 수 있습니다',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: colorScheme.outline,
                ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Connection status dot
// ---------------------------------------------------------------------------
class _ConnectionDot extends StatelessWidget {
  final bool connected;
  const _ConnectionDot({required this.connected});

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: connected ? '연결됨' : '연결 끊김',
      child: Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: connected ? Colors.greenAccent : Colors.red,
        ),
      ),
    );
  }
}
