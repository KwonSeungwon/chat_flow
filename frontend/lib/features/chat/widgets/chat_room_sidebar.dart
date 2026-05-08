import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/chat_room.dart';
import '../../auth/auth_provider.dart';
import '../../../core/services/fcm_service.dart';
import '../chat_provider.dart' show chatRoomsProvider, roomUnreadCountsProvider, roomNotificationPolicyProvider, NotificationPolicy, appStompServiceProvider, activeRoomIdProvider, roomSortProvider, RoomSortOption, HideRoomResult, roomKeywordsProvider, keywordAlertProvider, KeywordAlert;
import 'create_room_dialog.dart';
import 'sidebar_header.dart';
import 'room_tile.dart';


class ChatRoomSidebar extends ConsumerStatefulWidget {
  final String currentRoomId;
  final VoidCallback? onRoomSelected;
  final void Function(String roomId)? onSearchInRoom;

  const ChatRoomSidebar({
    super.key,
    required this.currentRoomId,
    this.onRoomSelected,
    this.onSearchInRoom,
  });

  @override
  ConsumerState<ChatRoomSidebar> createState() => _ChatRoomSidebarState();
}

class _ChatRoomSidebarState extends ConsumerState<ChatRoomSidebar>
    with WidgetsBindingObserver {
  Timer? _refreshTimer;
  bool _disposed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadInitialUnreadCounts();
    _startRefreshTimer();
    _initAppStomp();
  }

  /// Connect the app-level STOMP service for real-time unread increments.
  void _initAppStomp() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_disposed) return;
      final auth = ref.read(authProvider);
      if (auth.token == null || auth.userId == null) return;
      ref.read(appStompServiceProvider).connect(
        userId: auth.userId!,
        token: auth.token!,
        onRoomUpdate: (
          roomId,
          type, {
          List<String> mentionedUsernames = const [],
          String content = '',
          String senderUsername = '',
        }) {
          if (_disposed) return;
          if (type != 'UNREAD_INCREMENT') return;
          // Skip increment if user is currently viewing this room
          final activeRoom = ref.read(activeRoomIdProvider);
          if (activeRoom == roomId) return;

          final policy = ref.read(roomNotificationPolicyProvider.notifier).policyFor(roomId);
          final currentUsername = ref.read(authProvider).username;
          final isMentioningMe = mentionedUsernames.contains(currentUsername);
          // Keyword bypass: even MUTED/MENTIONS_ONLY rooms surface unread when
          // user-defined keywords appear in the content (frontend-only).
          final isKeywordHit =
              ref.read(roomKeywordsProvider.notifier).matches(roomId, content);

          // Policy-based filtering
          switch (policy) {
            case NotificationPolicy.muted:
              if (!isKeywordHit) return; // muted — only keyword hits surface
              break;
            case NotificationPolicy.mentionsOnly:
              if (!isMentioningMe && !isKeywordHit) return;
              break;
            case NotificationPolicy.all:
              break;
          }

          // Surface in-app keyword alert banner
          if (isKeywordHit) {
            final rooms = ref.read(chatRoomsProvider).valueOrNull ?? [];
            final room = rooms.where((r) => r.id == roomId).firstOrNull;
            final roomName = room?.name ?? roomId;
            final snippet = content.length > 60
                ? '${content.substring(0, 60)}...'
                : content;
            ref.read(keywordAlertProvider.notifier).state = KeywordAlert(
              roomId: roomId,
              roomName: roomName,
              senderUsername: senderUsername,
              snippet: snippet,
            );
          }

          final current = Map<String, int>.from(ref.read(roomUnreadCountsProvider));
          current[roomId] = (current[roomId] ?? 0) + 1;
          ref.read(roomUnreadCountsProvider.notifier).state = current;
        },
      );
    });
  }

  void _startRefreshTimer() {
    _refreshTimer?.cancel();
    _refreshTimer = Timer.periodic(const Duration(seconds: 30), (_) async {
      if (_disposed) return;
      ref.read(chatRoomsProvider.notifier).fetchRooms();
      final counts = await ref.read(chatRoomsProvider.notifier).fetchUnreadCounts();
      if (!_disposed && counts.isNotEmpty) {
        final current = Map<String, int>.from(ref.read(roomUnreadCountsProvider));
        current.addAll(counts);
        ref.read(roomUnreadCountsProvider.notifier).state = current;
      }
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      _refreshTimer?.cancel();
      _refreshTimer = null;
    } else if (state == AppLifecycleState.resumed && _refreshTimer == null) {
      _startRefreshTimer();
    }
  }

  Future<void> _loadInitialUnreadCounts() async {
    final counts = await ref.read(chatRoomsProvider.notifier).fetchUnreadCounts();
    if (!_disposed && counts.isNotEmpty) {
      ref.read(roomUnreadCountsProvider.notifier).state = Map<String, int>.from(counts);
    }
  }

  @override
  void dispose() {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
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
          SidebarHeader(
            onCreateTap: () => _showCreateDialog(context),
            onDmTap: () => _showDmDialog(context),
            onRefresh: () => ref.read(chatRoomsProvider.notifier).fetchRooms(),
            onSortSelected: (v) => ref.read(roomSortProvider.notifier).state = v,
            currentSort: ref.watch(roomSortProvider),
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
                    final policies = ref.watch(roomNotificationPolicyProvider);
                    final sort = ref.watch(roomSortProvider);
                    // Apply sort
                    final sortedRooms = [...rooms];
                    switch (sort) {
                      case RoomSortOption.recent:
                        sortedRooms.sort((a, b) {
                          final aT = a.lastMessageAt ?? a.createdAt ?? '';
                          final bT = b.lastMessageAt ?? b.createdAt ?? '';
                          return bT.compareTo(aT);
                        });
                      case RoomSortOption.unread:
                        sortedRooms.sort((a, b) {
                          final au = unreadCounts[a.id] ?? 0;
                          final bu = unreadCounts[b.id] ?? 0;
                          if (au == bu) {
                            final aT = a.lastMessageAt ?? a.createdAt ?? '';
                            final bT = b.lastMessageAt ?? b.createdAt ?? '';
                            return bT.compareTo(aT);
                          }
                          return bu.compareTo(au);
                        });
                      case RoomSortOption.name:
                        sortedRooms.sort((a, b) => a.name.compareTo(b.name));
                    }
                    return sortedRooms.isEmpty
                        ? RefreshIndicator(
                            onRefresh: () => ref.read(chatRoomsProvider.notifier).fetchRooms(),
                            child: LayoutBuilder(
                              builder: (context, constraints) => SingleChildScrollView(
                                physics: const AlwaysScrollableScrollPhysics(),
                                child: ConstrainedBox(
                                  constraints: BoxConstraints(minHeight: constraints.maxHeight),
                                  child: _EmptyRoomState(
                                      onCreateTap: () => _showCreateDialog(context)),
                                ),
                              ),
                            ),
                          )
                        : RefreshIndicator(
                            onRefresh: () => ref.read(chatRoomsProvider.notifier).fetchRooms(),
                            child: ListView.builder(
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.symmetric(
                                vertical: 8, horizontal: 8),
                            itemCount: sortedRooms.length,
                            itemBuilder: (context, index) {
                              final room = sortedRooms[index];
                              final unread = unreadCounts[room.id] ?? 0;
                              final policy = policies[room.id] ?? NotificationPolicy.all;
                              final keywords = ref.watch(roomKeywordsProvider)[room.id] ?? const <String>[];
                              final currentUserId = ref.watch(authProvider.select((s) => s.userId));
                              final isOwner = room.createdBy != null && room.createdBy == currentUserId;
                              return RoomTile(
                                room: room,
                                color: _roomColor(room),
                                isSelected: room.id == widget.currentRoomId,
                                isFull: room.isFull,
                                policy: policy,
                                unreadCount: unread,
                                keywords: keywords,
                                onTap: (room.isFull &&
                                        room.roomType != 'DIRECT' &&
                                        room.id != widget.currentRoomId)
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
                                onPolicyChange: (p) async {
                                  await ref.read(roomNotificationPolicyProvider.notifier).setPolicy(room.id, p);
                                  _applyFcmSubscription(ref, room.id, p);
                                },
                                onKeywordsTap: () => _showKeywordsDialog(context, room.id),
                                onDelete: isOwner ? () => _showDeleteRoomDialog(context, room) : null,
                                onHide: () => _showHideRoomDialog(context, room),
                                onSearchTap: widget.onSearchInRoom != null
                                    ? () => widget.onSearchInRoom!(room.id)
                                    : null,
                              );
                            },
                          ),
                          );
                  },
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _applyFcmSubscription(WidgetRef ref, String roomId, NotificationPolicy policy) async {
    final token = await FcmService.getToken();
    if (token == null) return;
    final dio = ref.read(dioClientProvider).dio;
    try {
      if (policy == NotificationPolicy.all) {
        await dio.post('/api/fcm/subscribe', data: {'token': token, 'roomId': roomId});
      } else {
        // MENTIONS_ONLY and MUTED both unsubscribe room topic
        // (mention-{username} topic remains subscribed so mentions still arrive)
        await dio.delete('/api/fcm/subscribe', data: {'token': token, 'roomId': roomId});
      }
    } catch (_) {/* best-effort */}
  }

  void _showDmDialog(BuildContext context) {
    final searchCtrl = TextEditingController();
    List<Map<String, dynamic>> results = [];
    showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('새 DM'),
          content: SizedBox(
            width: 280,
            height: 300,
            child: Column(
              children: [
                TextField(
                  controller: searchCtrl,
                  decoration: const InputDecoration(hintText: '사용자 검색 (2자 이상)', prefixIcon: Icon(Icons.search)),
                  onChanged: (q) async {
                    if (q.length < 2) { setDialogState(() => results = []); return; }
                    try {
                      final resp = await ref.read(dioClientProvider).dio.get('/api/users/search', queryParameters: {'q': q});
                      if (resp.data is Map && resp.data['data'] is List) {
                        setDialogState(() => results = (resp.data['data'] as List).cast<Map<String, dynamic>>());
                      }
                    } catch (_) {}
                  },
                ),
                const SizedBox(height: 8),
                Expanded(
                  child: ListView.builder(
                    itemCount: results.length,
                    itemBuilder: (_, i) {
                      final u = results[i];
                      return ListTile(
                        dense: true,
                        leading: CircleAvatar(radius: 16, child: Text((u['username'] ?? '?')[0].toUpperCase())),
                        title: Text(u['username'] ?? ''),
                        onTap: () async {
                          final dio = ref.read(dioClientProvider).dio;
                          final roomsNotifier = ref.read(chatRoomsProvider.notifier);
                          final router = GoRouter.of(context);
                          final messenger = ScaffoldMessenger.of(context);
                          Navigator.of(ctx).pop();
                          try {
                            final resp = await dio.post('/api/chat/rooms/dm', data: {
                              'targetUserId': u['userId'],
                              'targetUsername': u['username'],
                            });
                            final roomData = resp.data;
                            String? roomId;
                            if (roomData is Map && roomData['data'] is Map) {
                              roomId = roomData['data']['id']?.toString();
                            }
                            if (roomId != null) {
                              await roomsNotifier.fetchRooms();
                              router.go('/chat/$roomId');
                            }
                          } catch (_) {
                            messenger.showSnackBar(const SnackBar(content: Text('DM 생성에 실패했습니다.')));
                          }
                        },
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _showCreateDialog(BuildContext context) {
    showDialog(context: context, builder: (_) => const CreateRoomDialog());
  }

  void _showKeywordsDialog(BuildContext context, String roomId) {
    final current = ref.read(roomKeywordsProvider.notifier).keywordsFor(roomId);
    final controller = TextEditingController(text: current.join(', '));
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('키워드 알림'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '이 방에 다음 키워드가 포함된 메시지가 오면\n음소거 상태에서도 알림을 받습니다.',
              style: TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              autofocus: true,
              decoration: const InputDecoration(
                hintText: '예: 환자명, 약명, STAT',
                helperText: '콤마(,)로 구분',
                border: OutlineInputBorder(),
              ),
              maxLines: 2,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () async {
              final keywords = controller.text.split(',').map((e) => e.trim()).toList();
              await ref.read(roomKeywordsProvider.notifier).setKeywords(roomId, keywords);
              if (ctx.mounted) Navigator.of(ctx).pop();
            },
            child: const Text('저장'),
          ),
        ],
      ),
    );
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

  void _showHideRoomDialog(BuildContext context, ChatRoom room) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('방 숨기기'),
        content: const Text('이 대화를 목록에서 숨깁니다.\n상대가 새 메시지를 보내면 다시 보입니다.'),
        actionsAlignment: MainAxisAlignment.center,
        actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
        actions: [
          Row(
            children: [
              Expanded(
                child: TextButton(
                  onPressed: () => Navigator.of(ctx).pop(),
                  child: const Text('취소'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: FilledButton(
                  onPressed: () async {
                    Navigator.of(ctx).pop();
                    final result = await ref.read(chatRoomsProvider.notifier).hideRoom(room.id);
                    if (!context.mounted) return;
                    if (result != HideRoomResult.success) {
                      final msg = switch (result) {
                        HideRoomResult.notDmRoom => 'DM 방만 숨길 수 있습니다.',
                        HideRoomResult.notFound => '방을 찾을 수 없습니다.',
                        HideRoomResult.unauthorized => '인증이 만료되었습니다. 다시 로그인해주세요.',
                        HideRoomResult.serverError => '서버 오류로 숨기기에 실패했습니다. 잠시 후 다시 시도해주세요.',
                        HideRoomResult.networkError => '네트워크 오류로 숨기기에 실패했습니다.',
                        HideRoomResult.success => '',
                      };
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(msg)),
                      );
                      return;
                    }
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('방을 숨겼습니다.')),
                    );
                    if (context.mounted && room.id == widget.currentRoomId) {
                      context.go('/chat');
                    }
                  },
                  child: const Text('숨기기'),
                ),
              ),
            ],
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
        actionsAlignment: MainAxisAlignment.center,
        actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
        actions: [
          Row(
            children: [
              Expanded(
                child: TextButton(
                  onPressed: () => Navigator.of(ctx).pop(),
                  child: const Text('취소'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: FilledButton(
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
                    // Clean up per-room storage for the deleted room
                    ref.read(roomKeywordsProvider.notifier).removeRoom(room.id);
                    ref.read(roomNotificationPolicyProvider.notifier).removeRoom(room.id);
                    if (context.mounted && room.id == widget.currentRoomId) {
                      context.go('/chat');
                    }
                  },
                  child: const Text('삭제'),
                ),
              ),
            ],
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
