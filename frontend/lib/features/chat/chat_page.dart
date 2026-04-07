import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/theme_provider.dart';
import '../auth/auth_provider.dart';
import 'chat_provider.dart';
import 'widgets/chat_room_sidebar.dart';
import 'widgets/chat_messages_list.dart';
import 'widgets/chat_input.dart';
import 'widgets/create_room_dialog.dart';

class ChatPage extends ConsumerWidget {
  final String? roomId;

  const ChatPage({super.key, this.roomId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);

    // Reactive auth guard — redirects to login on 401 or token expiry
    if (!auth.isAuthenticated) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (context.mounted) context.go('/login');
      });
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    final themeMode = ref.watch(themeModeProvider);
    final isWide = MediaQuery.of(context).size.width >= 768;
    final effectiveRoomId = roomId;

    // Room info for AppBar
    final roomData = effectiveRoomId != null
        ? ref.watch(chatRoomsProvider).whenOrNull(
              data: (rooms) {
                final match = rooms.where((r) => r.id == effectiveRoomId);
                return match.isNotEmpty ? match.first : null;
              },
            )
        : null;
    final roomDisplayName = roomData?.name ?? effectiveRoomId ?? 'ChatFlow';

    return Scaffold(
      appBar: AppBar(
        leading: isWide
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
            Flexible(
              child: Text(roomDisplayName, overflow: TextOverflow.ellipsis),
            ),
            if (effectiveRoomId != null) ...[
              const SizedBox(width: 8),
              _ConnectionDot(
                connected: ref.watch(chatNotifierProvider(effectiveRoomId)).isConnected,
              ),
              if (roomData != null) ...[
                const SizedBox(width: 10),
                _ParticipantBadge(
                  count: roomData.participantCount,
                  max: roomData.maxParticipants,
                ),
              ],
            ],
          ],
        ),
        actions: [
          if (effectiveRoomId != null)
            _AiSummaryButton(roomId: effectiveRoomId),
          IconButton(
            icon: const Icon(Icons.search),
            tooltip: '메시지 검색',
            onPressed: () => context.push('/search'),
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (value) async {
              if (value == 'theme') {
                ref.read(themeModeProvider.notifier).state =
                    themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
              } else if (value == 'logout') {
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
              PopupMenuItem(
                value: 'theme',
                child: Row(
                  children: [
                    Icon(themeMode == ThemeMode.dark
                        ? Icons.light_mode_outlined
                        : Icons.dark_mode_outlined, size: 20),
                    const SizedBox(width: 8),
                    Text(themeMode == ThemeMode.dark ? '라이트 모드' : '다크 모드'),
                  ],
                ),
              ),
              const PopupMenuItem(value: 'logout', child: Text('로그아웃')),
            ],
          ),
        ],
      ),
      drawer: isWide
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
          if (isWide) ChatRoomSidebar(currentRoomId: effectiveRoomId ?? ''),
          if (isWide) const VerticalDivider(width: 1, thickness: 1),
          Expanded(
            child: effectiveRoomId != null
                ? _ChatRoomContent(roomId: effectiveRoomId, username: auth.username)
                : const _LobbyPlaceholder(),
          ),
        ],
      ),
      resizeToAvoidBottomInset: true,
    );
  }
}

// ---------------------------------------------------------------------------
// Participant badge in AppBar
// ---------------------------------------------------------------------------
class _ParticipantBadge extends StatelessWidget {
  final int count;
  final int max;
  const _ParticipantBadge({required this.count, required this.max});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: colorScheme.outline),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.people_outline_rounded,
              size: 13, color: colorScheme.onSurfaceVariant),
          const SizedBox(width: 4),
          Text(
            '$count/$max',
            style: TextStyle(
                fontSize: 12, color: colorScheme.onSurfaceVariant),
          ),
        ],
      ),
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
            isAiLoading: chatState.isAiLoading,
            readCounts: chatState.readCounts,
          ),
        ),
        ChatInput(
          isConnected: chatState.isConnected,
          isAiLoading: chatState.isAiLoading,
          isHandoff: ref.watch(chatRoomsProvider).maybeWhen(
            data: (rooms) => rooms.any((r) => r.id == roomId && r.isHandoff),
            orElse: () => false,
          ),
          onSend: (content, {String priority = 'ROUTINE'}) {
            chatNotifier.sendMessage(roomId: roomId, content: content, priority: priority);
          },
          onAskAi: (question) => chatNotifier.askAi(roomId, question),
          onSendPatientCard: (card) => chatNotifier.sendPatientCard(roomId, card),
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
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 88,
              height: 88,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    AppColors.primary.withAlpha(30),
                    AppColors.secondary.withAlpha(20),
                  ],
                ),
                border: Border.all(
                    color: AppColors.primary.withAlpha(60), width: 1),
              ),
              child: const Icon(Icons.forum_outlined,
                  size: 40, color: AppColors.primary),
            ),
            const SizedBox(height: 24),
            Text(
              '채팅을 시작하세요',
              style: TextStyle(
                color: colorScheme.onSurface,
                fontSize: 20,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '채팅방을 선택하거나 새로 만들어\n대화를 시작할 수 있습니다',
              textAlign: TextAlign.center,
              style: TextStyle(
                  color: colorScheme.onSurfaceVariant, fontSize: 14, height: 1.5),
            ),
            const SizedBox(height: 28),
            FilledButton.icon(
              onPressed: () => showDialog(
                context: context,
                builder: (_) => const CreateRoomDialog(),
              ),
              icon: const Icon(Icons.add_rounded),
              label: const Text('새 채팅방 만들기'),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// AI summary request button
// ---------------------------------------------------------------------------
class _AiSummaryButton extends ConsumerStatefulWidget {
  final String roomId;
  const _AiSummaryButton({required this.roomId});

  @override
  ConsumerState<_AiSummaryButton> createState() => _AiSummaryButtonState();
}

class _AiSummaryButtonState extends ConsumerState<_AiSummaryButton> {
  Future<void> _onTap() async {
    try {
      final msg = await ref
          .read(chatNotifierProvider(widget.roomId).notifier)
          .requestSummary(widget.roomId);
      if (!mounted) return;
      if (msg.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(msg), duration: const Duration(seconds: 3)),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('AI 요약을 요청했습니다. 잠시 후 채팅방에 표시됩니다.'),
            duration: Duration(seconds: 3),
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('요약 요청에 실패했습니다.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isSummaryLoading = ref.watch(
      chatNotifierProvider(widget.roomId).select((s) => s.isSummaryLoading),
    );
    return IconButton(
      icon: isSummaryLoading
          ? const SizedBox(
              width: 18, height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.auto_awesome, size: 20),
      tooltip: 'AI 대화 요약',
      onPressed: isSummaryLoading ? null : _onTap,
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
    final color = connected ? AppColors.success : AppColors.error;
    return Tooltip(
      message: connected ? '연결됨' : '연결 끊김',
      child: Container(
        width: 9,
        height: 9,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: color,
          boxShadow: [
            BoxShadow(color: color.withAlpha(120), blurRadius: 6, spreadRadius: 1),
          ],
        ),
      ),
    );
  }
}
