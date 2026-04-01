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
    final colors = [
      Colors.blue, Colors.green, Colors.orange, Colors.purple,
      Colors.red, Colors.teal, Colors.indigo, Colors.pink,
      Colors.cyan, Colors.amber,
    ];
    return colors[room.name.hashCode.abs() % colors.length];
  }

  @override
  Widget build(BuildContext context) {
    final roomsAsync = ref.watch(chatRoomsProvider);
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Container(
      width: 280,
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
                  icon: const Icon(Icons.add_circle_outline, size: 22),
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
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.wifi_off, size: 40, color: colorScheme.error),
                      const SizedBox(height: 12),
                      Text(
                        '채팅방 목록을 불러올 수 없습니다',
                        style: TextStyle(color: colorScheme.error, fontSize: 13),
                      ),
                      const SizedBox(height: 12),
                      OutlinedButton.icon(
                        onPressed: () => ref.read(chatRoomsProvider.notifier).fetchRooms(),
                        icon: const Icon(Icons.refresh, size: 16),
                        label: const Text('다시 시도'),
                      ),
                    ],
                  ),
                ),
              ),
              data: (rooms) {
                if (rooms.isEmpty) {
                  return _EmptyRoomState(onCreateTap: () => _showCreateDialog(context));
                }
                return ListView.builder(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  itemCount: rooms.length,
                  itemBuilder: (context, index) {
                    final room = rooms[index];
                    final isSelected = room.id == widget.currentRoomId;
                    final color = _roomColor(room);
                    final isFull = room.isFull;

                    return Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      child: Opacity(
                        opacity: isFull && !isSelected ? 0.55 : 1.0,
                        child: ListTile(
                          dense: true,
                          selected: isSelected,
                          selectedTileColor: colorScheme.primaryContainer.withAlpha(80),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10),
                          ),
                          leading: CircleAvatar(
                            radius: 18,
                            backgroundColor: color.withAlpha(40),
                            child: Text(
                              room.name.isNotEmpty ? room.name[0].toUpperCase() : '#',
                              style: TextStyle(
                                color: color,
                                fontWeight: FontWeight.bold,
                                fontSize: 15,
                              ),
                            ),
                          ),
                          title: Text(
                            room.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                              fontSize: 14,
                            ),
                          ),
                          subtitle: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (room.description != null && room.description!.isNotEmpty)
                                Padding(
                                  padding: const EdgeInsets.only(top: 2),
                                  child: Text(
                                    room.description!,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: colorScheme.onSurfaceVariant,
                                      fontSize: 11,
                                    ),
                                  ),
                                ),
                              Padding(
                                padding: const EdgeInsets.only(top: 2),
                                child: Row(
                                  children: [
                                    Icon(
                                      Icons.people_outline,
                                      size: 12,
                                      color: isFull
                                          ? colorScheme.error
                                          : colorScheme.onSurfaceVariant,
                                    ),
                                    const SizedBox(width: 3),
                                    Text(
                                      '${room.participantCount}/${room.maxParticipants}',
                                      style: TextStyle(
                                        fontSize: 11,
                                        color: isFull
                                            ? colorScheme.error
                                            : colorScheme.onSurfaceVariant,
                                        fontWeight: isFull ? FontWeight.bold : FontWeight.normal,
                                      ),
                                    ),
                                    if (isFull) ...[
                                      const SizedBox(width: 6),
                                      Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                                        decoration: BoxDecoration(
                                          color: colorScheme.errorContainer,
                                          borderRadius: BorderRadius.circular(4),
                                        ),
                                        child: Text(
                                          '만석',
                                          style: TextStyle(
                                            fontSize: 9,
                                            fontWeight: FontWeight.bold,
                                            color: colorScheme.onErrorContainer,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ],
                          ),
                          onTap: isFull && !isSelected
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

// ---------------------------------------------------------------------------
// Empty room list state with CTA
// ---------------------------------------------------------------------------
class _EmptyRoomState extends StatelessWidget {
  final VoidCallback onCreateTap;
  const _EmptyRoomState({required this.onCreateTap});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.chat_outlined, size: 48, color: colorScheme.outline),
            const SizedBox(height: 12),
            Text(
              '아직 채팅방이 없습니다',
              style: TextStyle(
                color: colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w500,
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '첫 번째 채팅방을 만들어보세요',
              style: TextStyle(
                color: colorScheme.outline,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton.tonal(
              onPressed: onCreateTap,
              child: const Text('채팅방 만들기'),
            ),
          ],
        ),
      ),
    );
  }
}
