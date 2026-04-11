import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/chat_room.dart';
import '../chat_provider.dart' show chatRoomsProvider, roomUnreadCountsProvider;
import 'create_room_dialog.dart';


class ChatRoomSidebar extends ConsumerStatefulWidget {
  final String currentRoomId;
  final VoidCallback? onRoomSelected;

  const ChatRoomSidebar({
    super.key,
    required this.currentRoomId,
    this.onRoomSelected,
  });

  @override
  ConsumerState<ChatRoomSidebar> createState() => _ChatRoomSidebarState();
}

class _ChatRoomSidebarState extends ConsumerState<ChatRoomSidebar> {
  Timer? _refreshTimer;
  bool _disposed = false;

  @override
  void initState() {
    super.initState();
    _refreshTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      if (!_disposed) ref.read(chatRoomsProvider.notifier).fetchRooms();
    });
  }

  @override
  void dispose() {
    _disposed = true;
    _refreshTimer?.cancel();
    super.dispose();
  }

  Color _roomColor(ChatRoom room) {
    if (room.color != null && room.color!.isNotEmpty) {
      try {
        final hex = room.color!.replaceFirst('#', '');
        return Color(int.parse('FF$hex', radix: 16));
      } catch (_) {}
    }
    return AppColors.avatarPalette[
        room.name.hashCode.abs() % AppColors.avatarPalette.length];
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final roomsAsync = ref.watch(chatRoomsProvider);

    return Container(
      width: 280,
      color: cs.surface,
      child: Column(
        children: [
          _SidebarHeader(
            onCreateTap: () => _showCreateDialog(context),
            onRefresh: () => ref.read(chatRoomsProvider.notifier).fetchRooms(),
          ),
          Divider(height: 1, color: cs.outline.withAlpha(60), thickness: 1),
          Expanded(
            child: roomsAsync.when(
              loading: () => const Center(
                  child: CircularProgressIndicator(strokeWidth: 2)),
              error: (_, __) => _ErrorState(
                onRetry: () =>
                    ref.read(chatRoomsProvider.notifier).fetchRooms(),
              ),
              data: (rooms) {
                    final unreadCounts = ref.watch(roomUnreadCountsProvider);
                    return rooms.isEmpty
                        ? _EmptyRoomState(
                            onCreateTap: () => _showCreateDialog(context))
                        : ListView.builder(
                            padding: const EdgeInsets.symmetric(
                                vertical: 8, horizontal: 8),
                            itemCount: rooms.length,
                            itemBuilder: (context, index) {
                              final room = rooms[index];
                              final unread = unreadCounts[room.id] ?? 0;
                              return _RoomTile(
                                room: room,
                                color: _roomColor(room),
                                isSelected: room.id == widget.currentRoomId,
                                isFull: room.isFull,
                                unreadCount: unread,
                                onTap: room.isFull &&
                                        room.id != widget.currentRoomId
                                    ? () => ScaffoldMessenger.of(context)
                                            .showSnackBar(
                                          const SnackBar(
                                            content: Text(
                                                '이 채팅방은 만석입니다 (최대 10명)'),
                                            duration: Duration(seconds: 2),
                                          ),
                                        )
                                    : () {
                                        if (room.isPrivate &&
                                            room.id != widget.currentRoomId) {
                                          _showPasswordDialog(context, room);
                                        } else {
                                          context.go('/chat/${room.id}');
                                          widget.onRoomSelected?.call();
                                        }
                                      },
                                onDelete: () => _showDeleteRoomDialog(context, room),
                              );
                            },
                          );
                  },
            ),
          ),
        ],
      ),
    );
  }

  void _showCreateDialog(BuildContext context) {
    showDialog(context: context, builder: (_) => const CreateRoomDialog());
  }

  void _showPasswordDialog(BuildContext context, ChatRoom room) {
    final pwCtrl = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Row(
          children: [
            const Icon(Icons.lock, size: 20),
            const SizedBox(width: 8),
            Text(room.name, style: const TextStyle(fontSize: 16)),
          ],
        ),
        content: TextField(
          controller: pwCtrl,
          obscureText: true,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: '비밀번호',
            prefixIcon: Icon(Icons.password),
          ),
          onSubmitted: (_) async {
            final ok = await _verifyAndJoin(ctx, room.id, pwCtrl.text.trim());
            if (ok && ctx.mounted) Navigator.of(ctx).pop();
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () async {
              final ok = await _verifyAndJoin(ctx, room.id, pwCtrl.text.trim());
              if (ok && ctx.mounted) Navigator.of(ctx).pop();
            },
            child: const Text('입장'),
          ),
        ],
      ),
    );
  }

  void _showDeleteRoomDialog(BuildContext context, ChatRoom room) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('채팅방 삭제'),
        content: Text('"${room.name}" 채팅방을 삭제하시겠습니까?\n모든 메시지가 함께 삭제됩니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('취소'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              Navigator.of(ctx).pop();
              final ok = await ref.read(chatRoomsProvider.notifier).deleteRoom(room.id);
              if (!ok && context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('채팅방 삭제에 실패했습니다.')),
                );
                return;
              }
              // Navigate away if currently in the deleted room
              if (context.mounted && room.id == widget.currentRoomId) {
                context.go('/chat');
              }
            },
            child: const Text('삭제'),
          ),
        ],
      ),
    );
  }

  Future<bool> _verifyAndJoin(BuildContext context, String roomId, String password) async {
    try {
      final dio = ref.read(dioClientProvider).dio;
      final resp = await dio.post('/api/chat/rooms/$roomId/verify', data: {'password': password});
      if (resp.statusCode == 200) {
        if (context.mounted) {
          context.go('/chat/$roomId');
          widget.onRoomSelected?.call();
        }
        return true;
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('비밀번호가 일치하지 않습니다.')),
        );
      }
    }
    return false;
  }
}

// ─────────────────────────────────────────────────────────────────
// Sidebar header
// ─────────────────────────────────────────────────────────────────
class _SidebarHeader extends StatelessWidget {
  final VoidCallback onCreateTap;
  final VoidCallback? onRefresh;
  const _SidebarHeader({required this.onCreateTap, this.onRefresh});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      height: 60,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      color: cs.surface,
      child: Row(
        children: [
          GestureDetector(
            onTap: onRefresh,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.asset('assets/app_icon.png', width: 32, height: 32,
                    errorBuilder: (_, __, ___) => const Icon(Icons.chat_bubble, size: 20, color: Colors.white)),
                ),
                const SizedBox(width: 10),
                Text(
                  'ChatFlow',
                  style: TextStyle(
                    color: cs.onSurface,
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.3,
                  ),
                ),
              ],
            ),
          ),
          const Spacer(),
          Tooltip(
            message: '새 채팅방',
            child: InkWell(
              onTap: onCreateTap,
              borderRadius: BorderRadius.circular(8),
              child: Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(8),
                  color: cs.surfaceContainer,
                  border: Border.all(color: cs.outline.withAlpha(80)),
                ),
                child: Icon(Icons.edit_outlined,
                    size: 16, color: cs.onSurfaceVariant),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Individual room tile
// ─────────────────────────────────────────────────────────────────
class _RoomTile extends StatefulWidget {
  final ChatRoom room;
  final Color color;
  final bool isSelected;
  final bool isFull;
  final int unreadCount;
  final VoidCallback onTap;
  final VoidCallback? onDelete;

  const _RoomTile({
    required this.room,
    required this.color,
    required this.isSelected,
    required this.isFull,
    required this.unreadCount,
    required this.onTap,
    this.onDelete,
  });

  @override
  State<_RoomTile> createState() => _RoomTileState();
}

class _RoomTileState extends State<_RoomTile> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    final bg = widget.isSelected
        ? AppColors.primary.withAlpha(22)
        : _hovered
            ? cs.surfaceContainerHigh
            : Colors.transparent;

    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: GestureDetector(
        onTap: widget.onTap,
        onLongPress: widget.onDelete,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          margin: const EdgeInsets.only(bottom: 2),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(12),
            border: widget.isSelected
                ? Border.all(color: AppColors.primary.withAlpha(55), width: 1)
                : null,
          ),
          child: Row(
            children: [
              // Left accent bar
              AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                width: 3,
                height: 44,
                margin: const EdgeInsets.only(left: 4, right: 10),
                decoration: BoxDecoration(
                  color: widget.isSelected
                      ? AppColors.primary
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),

              // Avatar + text (dimmed if full)
              Expanded(
                child: Opacity(
                  opacity:
                      widget.isFull && !widget.isSelected ? 0.48 : 1.0,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Row(
                      children: [
                        // Gradient avatar
                        Container(
                          width: 38,
                          height: 38,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [
                                widget.color.withAlpha(220),
                                widget.color.withAlpha(130),
                              ],
                            ),
                          ),
                          child: Center(
                            child: Text(
                              widget.room.name.isNotEmpty
                                  ? widget.room.name[0].toUpperCase()
                                  : '#',
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w700,
                                fontSize: 15,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),

                        // Name + participant count
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Row(
                                children: [
                                  if (widget.room.isHandoff) ...[
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                                      margin: const EdgeInsets.only(right: 4),
                                      decoration: BoxDecoration(
                                        color: const Color(0xFF00796B).withAlpha(25),
                                        borderRadius: BorderRadius.circular(3),
                                        border: Border.all(color: const Color(0xFF00796B).withAlpha(80), width: 0.5),
                                      ),
                                      child: const Text('SBAR', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: Color(0xFF00796B))),
                                    ),
                                  ],
                                  if (widget.room.isPrivate) ...[
                                    Icon(Icons.lock, size: 12,
                                        color: cs.onSurfaceVariant.withAlpha(150)),
                                    const SizedBox(width: 4),
                                  ],
                                  Expanded(
                                    child: Text(
                                widget.room.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: widget.isSelected
                                      ? cs.onSurface
                                      : cs.onSurfaceVariant,
                                  fontWeight: widget.isSelected
                                      ? FontWeight.w600
                                      : FontWeight.w400,
                                  fontSize: 14,
                                ),
                                  ),
                                ),
                                ],
                              ),
                              const SizedBox(height: 3),
                              Row(
                                children: [
                                  Icon(
                                    Icons.people_outline_rounded,
                                    size: 11,
                                    color: widget.isFull
                                        ? AppColors.error
                                        : cs.onSurfaceVariant.withAlpha(150),
                                  ),
                                  const SizedBox(width: 3),
                                  Text(
                                    '${widget.room.participantCount}/${widget.room.maxParticipants}',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: widget.isFull
                                          ? AppColors.error
                                          : cs.onSurfaceVariant.withAlpha(150),
                                      fontWeight: widget.isFull
                                          ? FontWeight.w600
                                          : FontWeight.w400,
                                    ),
                                  ),
                                  if (widget.isFull) ...[
                                    const SizedBox(width: 6),
                                    Container(
                                      padding: const EdgeInsets.symmetric(
                                          horizontal: 5, vertical: 1),
                                      decoration: BoxDecoration(
                                        color: AppColors.error.withAlpha(22),
                                        borderRadius: BorderRadius.circular(4),
                                        border: Border.all(
                                            color:
                                                AppColors.error.withAlpha(80),
                                            width: 1),
                                      ),
                                      child: const Text(
                                        '만석',
                                        style: TextStyle(
                                          fontSize: 9,
                                          color: AppColors.error,
                                          fontWeight: FontWeight.w700,
                                        ),
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ],
                          ),
                        ),
                        if (widget.unreadCount > 0) ...[
                          Container(
                            constraints: const BoxConstraints(minWidth: 18),
                            height: 18,
                            padding: const EdgeInsets.symmetric(horizontal: 5),
                            decoration: BoxDecoration(
                              color: AppColors.primary,
                              borderRadius: BorderRadius.circular(9),
                            ),
                            child: Center(
                              child: Text(
                                widget.unreadCount > 99
                                    ? '99+'
                                    : '${widget.unreadCount}',
                                style: const TextStyle(
                                  fontSize: 10,
                                  fontWeight: FontWeight.w700,
                                  color: Colors.white,
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 6),
                        ] else
                          const SizedBox(width: 8),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Error state
// ─────────────────────────────────────────────────────────────────
class _ErrorState extends StatelessWidget {
  final VoidCallback onRetry;
  const _ErrorState({required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.wifi_off_rounded,
                size: 36, color: cs.onSurfaceVariant.withAlpha(150)),
            const SizedBox(height: 12),
            Text(
              '채팅방 목록을 불러올 수 없습니다',
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded, size: 16),
              label: const Text('다시 시도'),
            ),
          ],
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────
class _EmptyRoomState extends StatelessWidget {
  final VoidCallback onCreateTap;
  const _EmptyRoomState({required this.onCreateTap});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: cs.surfaceContainer,
                border: Border.all(color: cs.outline.withAlpha(80)),
              ),
              child: Icon(Icons.chat_bubble_outline_rounded,
                  size: 26, color: cs.onSurfaceVariant.withAlpha(150)),
            ),
            const SizedBox(height: 16),
            Text(
              '아직 채팅방이 없습니다',
              style: TextStyle(
                color: cs.onSurfaceVariant,
                fontWeight: FontWeight.w600,
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '첫 번째 채팅방을 만들어보세요',
              style: TextStyle(
                  color: cs.onSurfaceVariant.withAlpha(150), fontSize: 12),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: onCreateTap,
              icon: const Icon(Icons.add_rounded, size: 18),
              label: const Text('채팅방 만들기'),
              style: FilledButton.styleFrom(minimumSize: const Size(0, 40)),
            ),
          ],
        ),
      ),
    );
  }
}
