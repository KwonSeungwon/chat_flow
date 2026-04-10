import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/theme_provider.dart';
import '../auth/auth_provider.dart';
import 'chat_provider.dart';
import 'widgets/chat_room_sidebar.dart';
import 'widgets/chat_messages_list.dart';
import 'widgets/chat_input.dart';
import 'widgets/create_room_dialog.dart';

String _buildProfileUrl(String relativeUrl) {
  // If already an absolute URL, return as-is
  if (relativeUrl.startsWith('http://') || relativeUrl.startsWith('https://')) {
    return relativeUrl;
  }
  if (kIsWeb) {
    final uri = Uri.base;
    final port = (uri.hasPort && uri.port != 80 && uri.port != 443) ? ':${uri.port}' : '';
    return '${uri.scheme}://${uri.host}$port$relativeUrl';
  }
  return relativeUrl;
}

Future<void> _changeProfileImage(BuildContext context, WidgetRef ref) async {
  try {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['jpg', 'jpeg', 'png', 'gif', 'webp'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.first;
    final bytes = file.bytes;
    if (bytes == null) return;

    final ext = file.extension?.toLowerCase() ?? 'jpg';
    const mimeMap = {'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'png': 'image/png', 'gif': 'image/gif', 'webp': 'image/webp'};
    final mimeType = mimeMap[ext] ?? 'image/jpeg';

    final dioClient = ref.read(dioClientProvider);
    final uploadResult = await dioClient.uploadFile(fileName: file.name, bytes: bytes, mimeType: mimeType);
    final fileUrl = uploadResult['fileUrl']?.toString() ?? '';
    if (fileUrl.isNotEmpty) {
      await ref.read(authProvider.notifier).updateProfileImage(fileUrl);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('프로필 이미지가 변경되었습니다.')));
      }
    }
  } catch (_) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('프로필 이미지 변경에 실패했습니다.')));
    }
  }
}

class ChatPage extends ConsumerWidget {
  final String? roomId;
  final String? scrollToMessageId;

  const ChatPage({super.key, this.roomId, this.scrollToMessageId});

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
                  roomId: effectiveRoomId,
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
            icon: CircleAvatar(
              radius: 16,
              backgroundImage: auth.profileImageUrl != null
                  ? NetworkImage(_buildProfileUrl(auth.profileImageUrl!))
                  : null,
              child: auth.profileImageUrl == null
                  ? const Icon(Icons.person, size: 18)
                  : null,
            ),
            onSelected: (value) async {
              if (value == 'theme') {
                ref.read(themeModeProvider.notifier).state =
                    themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
              } else if (value == 'profile') {
                await _changeProfileImage(context, ref);
              } else if (value == 'logout') {
                await ref.read(authProvider.notifier).logout();
                if (context.mounted) context.go('/login');
              }
            },
            itemBuilder: (context) => [
              PopupMenuItem(
                enabled: false,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    CircleAvatar(
                      radius: 30,
                      backgroundImage: auth.profileImageUrl != null
                          ? NetworkImage(_buildProfileUrl(auth.profileImageUrl!))
                          : null,
                      child: auth.profileImageUrl == null
                          ? const Icon(Icons.person, size: 30)
                          : null,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      auth.username.isNotEmpty ? auth.username : '사용자',
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                    ),
                    Text(
                      auth.role,
                      style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
              const PopupMenuDivider(),
              const PopupMenuItem(
                value: 'profile',
                child: Row(
                  children: [
                    Icon(Icons.camera_alt_outlined, size: 20),
                    SizedBox(width: 8),
                    Text('프로필 이미지 변경'),
                  ],
                ),
              ),
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
                ? _ChatRoomContent(
                    roomId: effectiveRoomId,
                    username: auth.username,
                    scrollToMessageId: scrollToMessageId,
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
// Participant badge in AppBar — tappable, shows participants modal
// ---------------------------------------------------------------------------
class _ParticipantBadge extends ConsumerWidget {
  final int count;
  final int max;
  final String roomId;

  const _ParticipantBadge({
    required this.count,
    required this.max,
    required this.roomId,
  });

  void _showModal(BuildContext context, WidgetRef ref) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _ParticipantsModal(roomId: roomId, count: count),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colorScheme = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: () => _showModal(context, ref),
      behavior: HitTestBehavior.opaque,
      child: Container(
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
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Participants bottom sheet modal
// ---------------------------------------------------------------------------
class _ParticipantsModal extends ConsumerStatefulWidget {
  final String roomId;
  final int count;

  const _ParticipantsModal({required this.roomId, required this.count});

  @override
  ConsumerState<_ParticipantsModal> createState() => _ParticipantsModalState();
}

class _ParticipantsModalState extends ConsumerState<_ParticipantsModal> {
  List<Map<String, dynamic>> _participants = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadParticipants();
  }

  Future<void> _loadParticipants() async {
    try {
      final dio = ref.read(dioClientProvider).dio;
      final resp =
          await dio.get('/api/chat/rooms/${widget.roomId}/participants');
      final data = resp.data;
      List<dynamic> list = [];
      if (data is Map && data['data'] is List) {
        list = data['data'] as List;
      } else if (data is List) {
        list = data;
      }
      if (mounted) {
        setState(() {
          _participants = list
              .map((e) => Map<String, dynamic>.from(e as Map))
              .toList();
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '참가자 목록을 불러올 수 없습니다';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Handle
          Container(
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: cs.outline.withAlpha(80),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Icon(Icons.people_rounded, size: 20, color: cs.primary),
                const SizedBox(width: 8),
                Text(
                  '참가자 (${_loading ? widget.count : _participants.length}명)',
                  style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          const Divider(height: 1),
          if (_loading)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 24),
              child: Text(_error!,
                  style: TextStyle(color: cs.onSurfaceVariant)),
            )
          else if (_participants.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 24),
              child: Text('현재 참가자 정보를 찾을 수 없습니다',
                  style: TextStyle(color: cs.onSurfaceVariant)),
            )
          else
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 320),
              child: ListView.builder(
                shrinkWrap: true,
                padding: const EdgeInsets.symmetric(vertical: 8),
                itemCount: _participants.length,
                itemBuilder: (context, index) {
                  final p = _participants[index];
                  final name = p['username'] ?? '알 수 없음';
                  final color = AppColors.avatarPalette[
                      name.hashCode.abs() % AppColors.avatarPalette.length];
                  return ListTile(
                    leading: CircleAvatar(
                      radius: 18,
                      backgroundColor: color.withAlpha(180),
                      child: Text(
                        name.isNotEmpty ? name[0].toUpperCase() : '?',
                        style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.w700,
                            fontSize: 13),
                      ),
                    ),
                    title: Text(name,
                        style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: cs.onSurface)),
                    dense: true,
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Active chat room content (messages + input)
// ---------------------------------------------------------------------------
class _ChatRoomContent extends ConsumerStatefulWidget {
  final String roomId;
  final String username;
  final String? scrollToMessageId;

  const _ChatRoomContent({
    required this.roomId,
    required this.username,
    this.scrollToMessageId,
  });

  @override
  ConsumerState<_ChatRoomContent> createState() => _ChatRoomContentState();
}

class _ChatRoomContentState extends ConsumerState<_ChatRoomContent> {
  @override
  void initState() {
    super.initState();
    // Clear unread count when user enters a room
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(chatNotifierProvider(widget.roomId).notifier)
          .markRoomRead(widget.roomId);
    });
  }

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(chatNotifierProvider(widget.roomId));
    final chatNotifier = ref.read(chatNotifierProvider(widget.roomId).notifier);

    // Determine scroll target: explicit messageId from search > lastRead on entry
    final scrollTarget = widget.scrollToMessageId ??
        (chatState.lastReadMessageId?.isNotEmpty == true
            ? chatState.lastReadMessageId
            : null);

    return Column(
      children: [
        if (chatState.isLoadingHistory) const LinearProgressIndicator(),
        Expanded(
          child: ChatMessagesList(
            messages: chatState.messages,
            currentUsername: widget.username,
            isAiLoading: chatState.isAiLoading,
            readCounts: chatState.readCounts,
            scrollToMessageId: scrollTarget,
            highlightMessageId: widget.scrollToMessageId,
            onReplySelected: (msg) => chatNotifier.setReplyTarget(msg),
          ),
        ),
        ChatInput(
          isConnected: chatState.isConnected,
          isAiLoading: chatState.isAiLoading,
          isHandoff: ref.watch(chatRoomsProvider).maybeWhen(
            data: (rooms) =>
                rooms.any((r) => r.id == widget.roomId && r.isHandoff),
            orElse: () => false,
          ),
          replyTarget: chatState.replyTarget,
          onCancelReply: () => chatNotifier.clearReplyTarget(),
          onSend: (content, {String priority = 'ROUTINE'}) {
            chatNotifier.sendMessage(
                roomId: widget.roomId, content: content, priority: priority);
          },
          onAskAi: (question) => chatNotifier.askAi(widget.roomId, question),
          onSendPatientCard: (card) =>
              chatNotifier.sendPatientCard(widget.roomId, card),
          onFilePick: (fileName, bytes, mimeType, content) =>
              chatNotifier.uploadAndSendFile(
                roomId: widget.roomId,
                fileName: fileName,
                bytes: bytes,
                mimeType: mimeType,
                content: content,
              ),
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
