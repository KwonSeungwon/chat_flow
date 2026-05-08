import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../chat_provider.dart' show RoomSortOption;
import '../mentions_provider.dart';

// ─────────────────────────────────────────────────────────────────
// Sidebar header
// ─────────────────────────────────────────────────────────────────
class SidebarHeader extends StatelessWidget {
  final VoidCallback onCreateTap;
  final VoidCallback? onDmTap;
  final VoidCallback? onRefresh;
  final void Function(RoomSortOption)? onSortSelected;
  final RoomSortOption currentSort;
  const SidebarHeader({super.key, required this.onCreateTap, this.onDmTap, this.onRefresh, this.onSortSelected, this.currentSort = RoomSortOption.recent});

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
          if (onSortSelected != null)
            PopupMenuButton<RoomSortOption>(
              icon: Icon(Icons.sort, size: 18, color: cs.onSurfaceVariant),
              tooltip: '정렬',
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
              onSelected: onSortSelected,
              itemBuilder: (ctx) => [
                PopupMenuItem(
                  value: RoomSortOption.recent,
                  child: Row(children: [
                    if (currentSort == RoomSortOption.recent)
                      Icon(Icons.check, size: 16, color: cs.primary)
                    else
                      const SizedBox(width: 16),
                    const SizedBox(width: 8),
                    const Text('최근 메시지 순'),
                  ]),
                ),
                PopupMenuItem(
                  value: RoomSortOption.unread,
                  child: Row(children: [
                    if (currentSort == RoomSortOption.unread)
                      Icon(Icons.check, size: 16, color: cs.primary)
                    else
                      const SizedBox(width: 16),
                    const SizedBox(width: 8),
                    const Text('미읽음 많은 순'),
                  ]),
                ),
                PopupMenuItem(
                  value: RoomSortOption.name,
                  child: Row(children: [
                    if (currentSort == RoomSortOption.name)
                      Icon(Icons.check, size: 16, color: cs.primary)
                    else
                      const SizedBox(width: 16),
                    const SizedBox(width: 8),
                    const Text('이름 순'),
                  ]),
                ),
              ],
            ),
          const SizedBox(width: 6),
          Consumer(builder: (_, ref, __) {
            final unread = ref.watch(mentionsProvider).unreadCount;
            return PopupMenuButton<String>(
              tooltip: '더 보기',
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
              icon: Stack(
                clipBehavior: Clip.none,
                children: [
                  Icon(Icons.more_horiz, size: 18, color: cs.onSurfaceVariant),
                  if (unread > 0)
                    Positioned(
                      key: const Key('sidebar-header-more-badge'),
                      right: -2,
                      top: -2,
                      child: Container(
                        width: 8,
                        height: 8,
                        decoration: BoxDecoration(
                          color: cs.error,
                          shape: BoxShape.circle,
                          border: Border.all(color: cs.surface, width: 1),
                        ),
                      ),
                    ),
                ],
              ),
              onSelected: (value) {
                if (value == 'mentions') {
                  context.go('/mentions');
                } else if (value == 'scheduled') {
                  context.go('/scheduled');
                } else if (value == 'dm' && onDmTap != null) {
                  onDmTap!();
                }
              },
              itemBuilder: (_) => [
                PopupMenuItem(
                  value: 'mentions',
                  child: Row(children: [
                    const Icon(Icons.alternate_email, size: 18),
                    const SizedBox(width: 8),
                    Expanded(child: Text(unread > 0 ? '내 멘션 ($unread)' : '내 멘션')),
                  ]),
                ),
                const PopupMenuItem(
                  value: 'scheduled',
                  child: Row(children: [
                    Icon(Icons.schedule_send_outlined, size: 18),
                    SizedBox(width: 8),
                    Text('예약된 메시지'),
                  ]),
                ),
                if (onDmTap != null)
                  const PopupMenuItem(
                    value: 'dm',
                    child: Row(children: [
                      Icon(Icons.person_add_outlined, size: 18),
                      SizedBox(width: 8),
                      Text('새 DM'),
                    ]),
                  ),
              ],
            );
          }),
          const SizedBox(width: 6),
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
