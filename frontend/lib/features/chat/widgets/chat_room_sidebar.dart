import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../shared/models/chat_room.dart';
import '../chat_provider.dart';
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

  @override
  void initState() {
    super.initState();
    _refreshTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      ref.read(chatRoomsProvider.notifier).fetchRooms();
    });
  }

  @override
  void dispose() {
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
    // Deterministic color from name
    final colors = [
      Colors.blue,
      Colors.green,
      Colors.orange,
      Colors.purple,
      Colors.red,
      Colors.teal,
      Colors.indigo,
      Colors.pink,
      Colors.cyan,
      Colors.amber,
    ];
    return colors[room.name.hashCode.abs() % colors.length];
  }

  @override
  Widget build(BuildContext context) {
    final roomsAsync = ref.watch(chatRoomsProvider);
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Container(
      width: 260,
      color: colorScheme.surfaceContainerLow,
      child: Column(
        children: [
          // Header
          Container(
            padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
            child: Row(
              children: [
                Icon(Icons.forum, color: colorScheme.primary, size: 20),
                const SizedBox(width: 8),
                Text(
                  '채팅방',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.add, size: 20),
                  tooltip: '새 채팅방',
                  onPressed: () => _showCreateDialog(context),
                ),
              ],
            ),
          ),
          const Divider(height: 1),

          // Room list
          Expanded(
            child: roomsAsync.when(
              loading: () => const Center(
                child: CircularProgressIndicator(),
              ),
              error: (e, _) => Center(
                child: Text('로딩 실패', style: TextStyle(color: colorScheme.error)),
              ),
              data: (rooms) {
                if (rooms.isEmpty) {
                  return const Center(child: Text('채팅방이 없습니다'));
                }
                return ListView.builder(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  itemCount: rooms.length,
                  itemBuilder: (context, index) {
                    final room = rooms[index];
                    final isSelected = room.id == widget.currentRoomId;
                    final color = _roomColor(room);

                    return Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 2,
                      ),
                      child: ListTile(
                        dense: true,
                        selected: isSelected,
                        selectedTileColor: colorScheme.primaryContainer
                            .withAlpha(80),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(8),
                        ),
                        leading: CircleAvatar(
                          radius: 16,
                          backgroundColor: color.withAlpha(40),
                          child: Text(
                            room.name.isNotEmpty
                                ? room.name[0].toUpperCase()
                                : '#',
                            style: TextStyle(
                              color: color,
                              fontWeight: FontWeight.bold,
                              fontSize: 14,
                            ),
                          ),
                        ),
                        title: Text(
                          room.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontWeight:
                                isSelected
                                    ? FontWeight.bold
                                    : FontWeight.normal,
                            fontSize: 14,
                          ),
                        ),
                        subtitle: Text(
                          '${room.participantCount}명 참여중',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                            fontSize: 11,
                          ),
                        ),
                        trailing:
                            room.isFull
                                ? Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 6,
                                    vertical: 2,
                                  ),
                                  decoration: BoxDecoration(
                                    color: colorScheme.errorContainer,
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    '만석',
                                    style: TextStyle(
                                      fontSize: 10,
                                      color: colorScheme.onErrorContainer,
                                    ),
                                  ),
                                )
                                : null,
                        onTap: room.isFull
                            ? () {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(
                                    content: Text('이 채팅방은 만석입니다 (최대 10명)'),
                                    duration: Duration(seconds: 2),
                                  ),
                                );
                              }
                            : () {
                                context.go('/chat/${room.id}');
                                widget.onRoomSelected?.call();
                              },
                      ),
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
    showDialog(
      context: context,
      builder: (ctx) => const CreateRoomDialog(),
    );
  }
}
