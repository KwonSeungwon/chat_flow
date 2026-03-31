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
  final String roomId;

  const ChatPage({super.key, required this.roomId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    final chatState = ref.watch(chatNotifierProvider(roomId));
    final chatNotifier = ref.read(chatNotifierProvider(roomId).notifier);
    final themeMode = ref.watch(themeModeProvider);
    final isWide = MediaQuery.of(context).size.width >= 600;

    final roomDisplayName = ref.watch(chatRoomsProvider).maybeWhen(
          data: (rooms) {
            final match = rooms.where((r) => r.id == roomId);
            return match.isNotEmpty ? match.first.name : roomId;
          },
          orElse: () => roomId,
        );

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
            const SizedBox(width: 8),
            _ConnectionDot(connected: chatState.isConnected),
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
                    currentRoomId: roomId,
                    onRoomSelected: () => Navigator.of(context).pop(),
                  ),
                ),
              ),
      body: Row(
        children: [
          // Sidebar (desktop only)
          if (isWide)
            ChatRoomSidebar(currentRoomId: roomId),

          // Divider between sidebar and content
          if (isWide)
            const VerticalDivider(width: 1, thickness: 1),

          // Main content
          Expanded(
            child: Column(
              children: [
                // Loading indicator
                if (chatState.isLoadingHistory)
                  const LinearProgressIndicator(),

                // Messages
                Expanded(
                  child: ChatMessagesList(
                    messages: chatState.messages,
                    currentUsername: auth.username,
                  ),
                ),

                // Input
                ChatInput(
                  isConnected: chatState.isConnected,
                  onSend: (content) {
                    chatNotifier.sendMessage(
                      roomId: roomId,
                      content: content,
                    );
                  },
                ),
              ],
            ),
          ),
        ],
      ),
      resizeToAvoidBottomInset: true,
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
