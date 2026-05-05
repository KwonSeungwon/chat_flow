import 'dart:async';
import 'dart:math' as math;
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants/chat_strings.dart';
import '../../core/constants/ui_constants.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/font_scale_provider.dart';
import '../../core/theme/theme_provider.dart';
import '../../core/utils/url_helper.dart';
import '../auth/auth_provider.dart';
import 'bookmark_provider.dart';
import 'chat_provider.dart';
import '../../shared/models/chat_message.dart';
import '../../shared/models/chat_room.dart';
import 'widgets/chat_room_sidebar.dart';
import 'widgets/chat_messages_list.dart';
import 'widgets/chat_input.dart';
import 'widgets/create_room_dialog.dart';
import 'dialogs/change_password_dialog.dart';
import 'widgets/in_room_search_sheet.dart';
import 'admin/widgets/room_members_sheet.dart';
import 'admin/widgets/moderator_queue_sheet.dart';
import 'admin/admin_event_listener.dart';
import 'admin/admin_event_state.dart';
import 'admin/current_room_role_provider.dart';
import '../../shared/models/room_role.dart';
import '../profile/widgets/profile_edit_dialog.dart';


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

void _showProfileDialog(BuildContext context, WidgetRef ref) {
  final auth = ref.read(authProvider);
  showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('프로필 관리'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _ProfileAvatar(
            url: auth.profileImageUrl != null ? buildFullUrl(auth.profileImageUrl!) : null,
            radius: 40,
          ),
          const SizedBox(height: 12),
          Text(auth.username.isNotEmpty ? auth.username : '사용자',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
          const SizedBox(height: 4),
          Text(auth.role, style: TextStyle(fontSize: 14, color: Theme.of(context).colorScheme.onSurfaceVariant)),
          const SizedBox(height: 4),
          if (auth.userId != null)
            Text('ID: ${auth.userId}', style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120))),
          const SizedBox(height: 16),
          // Font scale selector
          Align(
            alignment: Alignment.centerLeft,
            child: Text('글꼴 크기', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Theme.of(context).colorScheme.onSurfaceVariant)),
          ),
          const SizedBox(height: 6),
          Consumer(builder: (_, ref, __) {
            final current = ref.watch(fontScaleProvider);
            return SegmentedButton<FontScale>(
              segments: FontScale.values
                  .map((e) => ButtonSegment(value: e, label: Text(e.label)))
                  .toList(),
              selected: {current},
              onSelectionChanged: (set) =>
                  ref.read(fontScaleProvider.notifier).set(set.first),
              style: SegmentedButton.styleFrom(
                textStyle: const TextStyle(fontSize: 13),
              ),
            );
          }),
        ],
      ),
      actionsAlignment: MainAxisAlignment.center,
      actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
      actions: [
        Row(children: [
          Expanded(child: OutlinedButton.icon(
            icon: const Icon(Icons.camera_alt_outlined, size: 18),
            label: const Text('이미지 변경'),
            onPressed: () async {
              Navigator.of(ctx).pop();
              await _changeProfileImage(context, ref);
            },
          )),
          const SizedBox(width: 8),
          Expanded(child: OutlinedButton.icon(
            icon: const Icon(Icons.lock_outline, size: 18),
            label: const Text('비밀번호'),
            onPressed: () {
              Navigator.of(ctx).pop();
              showDialog(
                context: context,
                barrierDismissible: false,
                builder: (_) => const ChangePasswordDialog(),
              );
            },
          )),
        ]),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton.icon(
            icon: const Icon(Icons.bookmark_outline, size: 18),
            label: const Text('북마크'),
            onPressed: () {
              Navigator.of(ctx).pop();
              _showBookmarksDialog(context, ref);
            },
          ),
        ),
      ],
    ),
  );
}

void _showBookmarksDialog(BuildContext context, WidgetRef ref) {
  showDialog(
    context: context,
    builder: (ctx) {
      final mq = MediaQuery.of(ctx);
      return Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 500, maxHeight: 600),
          child: SizedBox(
            width: math.min(500.0, mq.size.width - 32),
            height: math.min(600.0, mq.size.height - 120),
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 16, 8, 8),
                  child: Row(
                    children: [
                      const Icon(Icons.bookmark, size: 20),
                      const SizedBox(width: 8),
                      const Text('북마크', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                      const Spacer(),
                      IconButton(
                        icon: const Icon(Icons.close, size: 20),
                        onPressed: () => Navigator.of(ctx).pop(),
                      ),
                    ],
                  ),
                ),
                const Divider(height: 1),
                Expanded(
                  child: Consumer(builder: (_, ref, __) {
                    final bookmarks = ref.watch(bookmarksProvider);
                    if (bookmarks.isEmpty) {
                      return Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.bookmark_border, size: 48, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(100)),
                            const SizedBox(height: 12),
                            Text('저장된 북마크가 없습니다.', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
                          ],
                        ),
                      );
                    }
                    return ListView.separated(
                      itemCount: bookmarks.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (_, i) {
                        final b = bookmarks[i];
                        String formattedTime = b.timestamp;
                        try {
                          final dt = DateTime.parse(b.timestamp).toLocal();
                          formattedTime = '${dt.month}/${dt.day} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
                        } catch (_) {}
                        return ListTile(
                          title: Text(b.content, maxLines: 2, overflow: TextOverflow.ellipsis),
                          subtitle: Text('${b.username} · $formattedTime', style: const TextStyle(fontSize: 11)),
                          trailing: IconButton(
                            icon: const Icon(Icons.delete_outline, size: 18),
                            onPressed: () => ref.read(bookmarksProvider.notifier).remove(b.messageId),
                          ),
                          onTap: () {
                            Navigator.of(ctx).pop();
                            GoRouter.of(context).go('/chat/${b.roomId}?messageId=${b.messageId}');
                          },
                        );
                      },
                    );
                  }),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

Future<void> _copyInviteLink(BuildContext context, WidgetRef ref, String roomId) async {
  try {
    final dio = ref.read(dioClientProvider).dio;
    final resp = await dio.post('/api/chat/rooms/$roomId/invite-link');
    final data = resp.data;
    String? url;
    if (data is Map && data['data'] is Map) {
      url = (data['data'] as Map)['url']?.toString();
    }
    if (url == null || url.isEmpty) throw Exception('url empty');
    await Clipboard.setData(ClipboardData(text: url));
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('초대 링크가 클립보드에 복사되었습니다 (24시간 유효)'),
          duration: Duration(seconds: 3),
        ),
      );
    }
  } catch (_) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('초대 링크 생성에 실패했습니다')),
      );
    }
  }
}

void _showRoomSettingsDialog(BuildContext context, WidgetRef ref, String roomId, ChatRoom room) {
  final nameCtrl = TextEditingController(text: room.name);
  final descCtrl = TextEditingController(text: room.description ?? '');

  // 모바일(<600px)은 bottom sheet + 풀 스크롤, 데스크톱은 Dialog로 분기.
  final mq = MediaQuery.of(context);
  final isMobile = mq.size.width < 600;

  Future<void> save(BuildContext dialogCtx) async {
    try {
      await ref.read(dioClientProvider).dio.put('/api/chat/rooms/$roomId/settings', data: {
        'name': nameCtrl.text.trim(),
        'description': descCtrl.text.trim(),
      });
      if (dialogCtx.mounted) Navigator.of(dialogCtx).pop();
      ref.read(chatRoomsProvider.notifier).fetchRooms();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('채팅방 설정이 변경되었습니다.')));
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('설정 변경에 실패했습니다.')));
      }
    }
  }

  Widget buildBody(BuildContext dialogCtx) {
    return SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: nameCtrl,
            decoration: const InputDecoration(
              labelText: '채팅방 이름',
              border: OutlineInputBorder(),
              isDense: true,
            ),
            textInputAction: TextInputAction.next,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: descCtrl,
            decoration: const InputDecoration(
              labelText: '설명',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
            ),
            maxLines: 3,
            minLines: 2,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: TextButton(
                onPressed: () => Navigator.of(dialogCtx).pop(),
                child: const Text('취소'),
              )),
              const SizedBox(width: 8),
              Expanded(child: FilledButton(
                onPressed: () => save(dialogCtx),
                child: const Text('저장'),
              )),
            ],
          ),
        ],
      ),
    );
  }

  if (isMobile) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 4,
          bottom: MediaQuery.of(ctx).viewInsets.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.only(bottom: 12),
              child: Text('채팅방 설정',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            ),
            buildBody(ctx),
          ],
        ),
      ),
    );
  } else {
    showDialog(
      context: context,
      builder: (ctx) => Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Padding(
                  padding: EdgeInsets.only(bottom: 16),
                  child: Text('채팅방 설정',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                ),
                buildBody(ctx),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

void _showForwardDialog(BuildContext context, WidgetRef ref, ChatNotifier currentNotifier, ChatMessage msg) {
  final rooms = ref.read(chatRoomsProvider).valueOrNull ?? [];
  showDialog(
    context: context,
    builder: (ctx) {
      String filter = '';
      return StatefulBuilder(
        builder: (ctx, setState) {
          final filtered = filter.isEmpty
              ? rooms
              : rooms.where((r) => r.name.toLowerCase().contains(filter.toLowerCase())).toList();
          final fwdMq = MediaQuery.of(ctx);
          final fwdMobile = fwdMq.size.width < 600;
          return AlertDialog(
            title: const Text('메시지 전달'),
            content: SizedBox(
              width: fwdMobile ? math.min(fwdMq.size.width - 64, 360.0) : 280,
              height: math.min(360.0, fwdMq.size.height - 200),
              child: Column(
                children: [
                  TextField(
                    decoration: const InputDecoration(
                      hintText: '방 검색',
                      prefixIcon: Icon(Icons.search, size: 20),
                      isDense: true,
                    ),
                    onChanged: (v) => setState(() => filter = v),
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: filtered.isEmpty
                        ? const Center(child: Text('일치하는 방이 없습니다.'))
                        : ListView.builder(
                            itemCount: filtered.length,
                            itemBuilder: (_, i) {
                              final room = filtered[i];
                              return ListTile(
                                leading: CircleAvatar(radius: 16, child: Text(room.name.isNotEmpty ? room.name[0].toUpperCase() : '#')),
                                title: Text(room.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                                onTap: () async {
                                  final messenger = ScaffoldMessenger.of(context);
                                  Navigator.of(ctx).pop();
                                  final ok = await currentNotifier.forwardMessage(room.id, msg);
                                  messenger.showSnackBar(SnackBar(
                                    content: Text(ok
                                        ? '"${room.name}"에 메시지를 전달했습니다.'
                                        : '연결 상태를 확인하고 다시 시도해주세요.'),
                                  ));
                                },
                              );
                            },
                          ),
                  ),
                ],
              ),
            ),
          );
        },
      );
    },
  );
}

void _showInRoomSearch(BuildContext context, WidgetRef ref, String roomId) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (ctx) => InRoomSearchSheet(
      roomId: roomId,
      onResultTap: (messageId) {
        GoRouter.of(context).go('/chat/$roomId?messageId=$messageId');
      },
    ),
  );
}

void _showReadersSheet(BuildContext context, WidgetRef ref, String roomId, String messageId, List<ChatMessage> messages) async {
  try {
    final resp = await ref.read(dioClientProvider).dio.get('/api/chat/rooms/$roomId/readers');
    final data = resp.data;
    // positions: {userId: lastReadMessageId}
    Map<String, String> positions = {};
    if (data is Map && data['data'] is Map) {
      positions = Map<String, String>.from(data['data'] as Map);
    }

    // Find the index of target message to compare read positions
    final targetIdx = messages.indexWhere((m) => m.effectiveId == messageId);
    if (targetIdx < 0) return;

    // Users who have read at or past the target message
    final readers = <String>[];
    for (final entry in positions.entries) {
      final readerLastReadId = entry.value;
      final readerIdx = messages.indexWhere((m) => m.effectiveId == readerLastReadId);
      if (readerIdx >= targetIdx) {
        // Find username from messages sent by this userId
        String? username;
        for (final m in messages) {
          if (m.userId == entry.key) { username = m.username; break; }
        }
        readers.add(username ?? entry.key);
      }
    }

    if (!context.mounted) return;
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            Container(width: 36, height: 4, decoration: BoxDecoration(color: Colors.grey.withAlpha(80), borderRadius: BorderRadius.circular(2))),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text('읽은 사람 (${readers.length}명)', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
            ),
            if (readers.isEmpty)
              const Padding(padding: EdgeInsets.all(16), child: Text('읽은 사용자가 없습니다.'))
            else
              ...readers.map((name) => ListTile(
                leading: CircleAvatar(radius: 16, child: Text(name.isNotEmpty ? name[0].toUpperCase() : '?', style: const TextStyle(fontSize: 14))),
                title: Text(name),
              )),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  } catch (_) {}
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

    return PopScope(
      canPop: effectiveRoomId == null || isWide,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        if (effectiveRoomId != null && !isWide) {
          context.go('/chat');
        }
      },
      child: AdminEventListener(
      roomId: effectiveRoomId,
      child: Scaffold(
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
              const SizedBox(width: 6),
              _ConnectionDot(
                connected: ref.watch(chatNotifierProvider(effectiveRoomId)).isConnected,
              ),
              // Participant badge only on wide screens to save AppBar space
              if (isWide && roomData != null) ...[
                const SizedBox(width: 10),
                Builder(builder: (context) {
                  final realtimeCount = ref.watch(
                    chatNotifierProvider(effectiveRoomId).select((s) => s.participantCount),
                  );
                  return _ParticipantBadge(
                    count: realtimeCount ?? roomData.participantCount,
                    max: roomData.maxParticipants,
                    roomId: effectiveRoomId,
                  );
                }),
              ],
            ],
          ],
        ),
        actions: [
          // 운영자(OWNER/MOD)에게만 노출되는 방 관리 진입점
          if (effectiveRoomId != null) Builder(builder: (context) {
            final myRole = ref.watch(currentRoomRoleProvider(effectiveRoomId));
            if (myRole != RoomRole.owner && myRole != RoomRole.moderator) {
              return const SizedBox.shrink();
            }
            return IconButton(
              icon: const Icon(Icons.shield_outlined, size: 20),
              tooltip: '방 관리 (신고 처리)',
              onPressed: () => showModeratorQueueSheet(context, effectiveRoomId),
            );
          }),
          // Wide (>=768): show all action buttons individually
          if (isWide) ...[
            if (effectiveRoomId != null && roomData != null)
              IconButton(
                icon: const Icon(Icons.settings_outlined, size: 20),
                tooltip: '채팅방 설정',
                onPressed: () => _showRoomSettingsDialog(context, ref, effectiveRoomId, roomData),
              ),
            if (effectiveRoomId != null)
              IconButton(
                icon: const Icon(Icons.person_add_outlined, size: 20),
                tooltip: '초대 링크 복사',
                onPressed: () => _copyInviteLink(context, ref, effectiveRoomId),
              ),
            if (effectiveRoomId != null)
              _AiSummaryButton(roomId: effectiveRoomId),
            if (effectiveRoomId != null)
              IconButton(
                icon: const Icon(Icons.manage_search, size: 22),
                tooltip: '방 내 검색',
                onPressed: () => _showInRoomSearch(context, ref, effectiveRoomId),
              ),
            IconButton(
              icon: const Icon(Icons.search),
              tooltip: '전체 검색',
              onPressed: () => context.push('/search'),
            ),
          ],
          // Mobile (<768): merge room actions + search into overflow menu
          if (!isWide) ...[
            if (effectiveRoomId != null)
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert, size: 22),
                tooltip: '메뉴',
                onSelected: (value) {
                  if (value == 'invite_link') {
                    _copyInviteLink(context, ref, effectiveRoomId);
                  } else if (value == 'settings' && roomData != null) {
                    _showRoomSettingsDialog(context, ref, effectiveRoomId, roomData);
                  } else if (value == 'ai_summary') {
                    ref.read(chatNotifierProvider(effectiveRoomId).notifier)
                        .requestSummary(effectiveRoomId)
                        .then((msg) {
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                        content: Text(msg.isNotEmpty ? msg : 'AI 요약을 요청했습니다. 잠시 후 채팅방에 표시됩니다.'),
                        duration: const Duration(seconds: 3),
                      ));
                    }).catchError((_) {
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('요약 요청에 실패했습니다.')));
                      }
                    });
                  } else if (value == 'room_search') {
                    _showInRoomSearch(context, ref, effectiveRoomId);
                  } else if (value == 'global_search') {
                    context.push('/search');
                  } else if (value == 'participants') {
                    showRoomMembersSheet(context, effectiveRoomId);
                  }
                },
                itemBuilder: (context) => [
                  if (roomData != null)
                    PopupMenuItem(
                      value: 'participants',
                      child: Row(
                        children: [
                          const Icon(Icons.people_outline, size: 20),
                          const SizedBox(width: 8),
                          Text('참가자 (${roomData.participantCount}명)'),
                        ],
                      ),
                    ),
                  if (roomData != null)
                    const PopupMenuItem(
                      value: 'settings',
                      child: Row(
                        children: [
                          Icon(Icons.settings_outlined, size: 20),
                          SizedBox(width: 8),
                          Text('채팅방 설정'),
                        ],
                      ),
                    ),
                  const PopupMenuItem(
                    value: 'invite_link',
                    child: Row(
                      children: [
                        Icon(Icons.person_add_outlined, size: 20),
                        SizedBox(width: 8),
                        Text('초대 링크 복사'),
                      ],
                    ),
                  ),
                  const PopupMenuItem(
                    value: 'ai_summary',
                    child: Row(
                      children: [
                        Icon(Icons.auto_awesome, size: 20),
                        SizedBox(width: 8),
                        Text('AI 대화 요약'),
                      ],
                    ),
                  ),
                  const PopupMenuItem(
                    value: 'room_search',
                    child: Row(
                      children: [
                        Icon(Icons.manage_search, size: 20),
                        SizedBox(width: 8),
                        Text('방 내 검색'),
                      ],
                    ),
                  ),
                  const PopupMenuDivider(),
                  const PopupMenuItem(
                    value: 'global_search',
                    child: Row(
                      children: [
                        Icon(Icons.search, size: 20),
                        SizedBox(width: 8),
                        Text('전체 검색'),
                      ],
                    ),
                  ),
                ],
              ),
            if (effectiveRoomId == null)
              IconButton(
                icon: const Icon(Icons.search),
                tooltip: '전체 검색',
                onPressed: () => context.push('/search'),
              ),
          ],
          PopupMenuButton<String>(
            icon: _ProfileAvatar(
              url: auth.profileImageUrl != null
                  ? buildFullUrl(auth.profileImageUrl!)
                  : null,
              radius: 16,
            ),
            onSelected: (value) async {
              if (value == 'theme') {
                ref.read(themeModeProvider.notifier).toggle();
              } else if (value == 'profile') {
                if (context.mounted) _showProfileDialog(context, ref);
              } else if (value == 'profile_edit') {
                if (context.mounted) showProfileEditDialog(context);
              } else if (value == 'bookmarks') {
                if (context.mounted) _showBookmarksDialog(context, ref);
              } else if (value == 'password') {
                if (context.mounted) {
                  showDialog(
                    context: context,
                    barrierDismissible: false,
                    builder: (_) => const ChangePasswordDialog(),
                  );
                }
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
                    _ProfileAvatar(
                      url: auth.profileImageUrl != null
                          ? buildFullUrl(auth.profileImageUrl!)
                          : null,
                      radius: 30,
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
                value: 'profile_edit',
                child: Row(
                  children: [
                    Icon(Icons.edit_outlined, size: 20),
                    SizedBox(width: 8),
                    Text('프로필 편집'),
                  ],
                ),
              ),
              const PopupMenuItem(
                value: 'profile',
                child: Row(
                  children: [
                    Icon(Icons.person_outline, size: 20),
                    SizedBox(width: 8),
                    Text('계정 / 환경설정'),
                  ],
                ),
              ),
              const PopupMenuItem(
                value: 'bookmarks',
                child: Row(
                  children: [
                    Icon(Icons.bookmark_outline, size: 20),
                    SizedBox(width: 8),
                    Text('북마크'),
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
              const PopupMenuItem(
                value: 'password',
                child: Row(
                  children: [
                    Icon(Icons.lock_outline, size: 20),
                    SizedBox(width: 8),
                    Text('비밀번호 변경'),
                  ],
                ),
              ),
              const PopupMenuDivider(),
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
                  onSearchInRoom: (roomId) {
                    Navigator.of(context).pop();
                    if (context.mounted) _showInRoomSearch(context, ref, roomId);
                  },
                ),
              ),
            ),
      body: Row(
        children: [
          if (isWide) ChatRoomSidebar(
            currentRoomId: effectiveRoomId ?? '',
            onSearchInRoom: (roomId) => _showInRoomSearch(context, ref, roomId),
          ),
          if (isWide) const VerticalDivider(width: 1, thickness: 1),
          Expanded(
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1100),
                child: effectiveRoomId != null
                    ? _ChatRoomContent(
                        roomId: effectiveRoomId,
                        username: auth.username,
                        scrollToMessageId: scrollToMessageId,
                      )
                    : const _LobbyPlaceholder(),
              ),
            ),
          ),
        ],
      ),
      resizeToAvoidBottomInset: true,
    ),
    ),
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
    // 운영 도구 통합 멤버 시트로 일원화 — 역할 배지 + 강퇴/뮤트/위임/ban 액션 포함.
    showRoomMembersSheet(context, roomId);
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
  String? _replyScrollTarget;
  bool _showSearch = false;
  final _searchCtrl = TextEditingController();
  final _keyboardFocusNode = FocusNode();
  List<ChatMessage> _searchResults = [];
  bool _searching = false;

  Future<void> _doSearch(String query) async {
    if (query.trim().isEmpty) {
      setState(() => _searchResults = []);
      return;
    }
    setState(() => _searching = true);
    try {
      final resp = await ref.read(dioClientProvider).dio.get(
        '/api/search/rooms/${widget.roomId}/messages',
        queryParameters: {'query': query.trim()},
      );
      final data = resp.data;
      List<dynamic> items = [];
      if (data is Map && data['data'] is List) {
        items = data['data'] as List;
      } else if (data is List) {
        items = data;
      }
      setState(() {
        _searchResults = items.map((e) => ChatMessage.fromJson(e as Map<String, dynamic>)).toList();
        _searching = false;
      });
    } catch (_) {
      setState(() => _searching = false);
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      // Track which room the user is viewing so AppStompService can skip unread increments
      ref.read(activeRoomIdProvider.notifier).state = widget.roomId;
      ref.read(chatNotifierProvider(widget.roomId).notifier)
          .markRoomRead(widget.roomId);
    });
  }

  @override
  void dispose() {
    // Clear active room on leave so unread increments resume for this room
    ref.read(activeRoomIdProvider.notifier).state = null;
    _searchCtrl.dispose();
    _keyboardFocusNode.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(_ChatRoomContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Clear reply-scroll override when an explicit search target arrives
    if (widget.scrollToMessageId != oldWidget.scrollToMessageId &&
        widget.scrollToMessageId != null) {
      _replyScrollTarget = null;
    }
    // Clear keyword alert when navigating to the alerted room
    if (widget.roomId != oldWidget.roomId) {
      final alert = ref.read(keywordAlertProvider);
      if (alert != null && alert.roomId == widget.roomId) {
        Future.microtask(() => ref.read(keywordAlertProvider.notifier).state = null);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(chatNotifierProvider(widget.roomId));
    final chatNotifier = ref.read(chatNotifierProvider(widget.roomId).notifier);

    // Auto-clear keyword alert when viewing the alerted room
    ref.listen<KeywordAlert?>(keywordAlertProvider, (prev, next) {
      if (next != null && next.roomId == widget.roomId) {
        Future.microtask(() => ref.read(keywordAlertProvider.notifier).state = null);
      }
    });

    // Route away on room exit (deleted or full)
    ref.listen(chatNotifierProvider(widget.roomId), (_, next) {
      if (!context.mounted) return;
      if (next.exitReason == ChatExitReason.deleted) {
        context.go('/chat');
      } else if (next.exitReason == ChatExitReason.full) {
        final redirectTo = next.redirectTo;
        if (redirectTo != null && redirectTo.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('채팅방이 가득 찼습니다. 새 방으로 이동합니다.')),
          );
          context.go('/chat/$redirectTo');
        } else {
          context.go('/chat');
        }
      }
    });

    // Determine scroll target: reply-tap > explicit search > lastRead on entry
    final scrollTarget = _replyScrollTarget ?? widget.scrollToMessageId ??
        (chatState.lastReadMessageId?.isNotEmpty == true
            ? chatState.lastReadMessageId
            : null);

    return KeyboardListener(
      focusNode: _keyboardFocusNode,
      autofocus: false,
      onKeyEvent: (event) {
        if (event is KeyDownEvent &&
            event.logicalKey == LogicalKeyboardKey.keyF &&
            HardwareKeyboard.instance.isControlPressed) {
          setState(() => _showSearch = !_showSearch);
        }
      },
      child: Column(
      children: [
        // Inline room search (Ctrl+F or tap search icon)
        if (!_showSearch && !chatState.isLoadingHistory)
          Align(
            alignment: Alignment.centerRight,
            child: Padding(
              padding: const EdgeInsets.only(right: 8, top: 2),
              child: IconButton(
                icon: Icon(Icons.find_in_page_outlined, size: 18, color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(120)),
                tooltip: '이 채팅방에서 검색 (Ctrl+F)',
                onPressed: () => setState(() => _showSearch = true),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
              ),
            ),
          ),
        if (_showSearch)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            color: Theme.of(context).colorScheme.surfaceContainer,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(children: [
                  Expanded(
                    child: TextField(
                      controller: _searchCtrl,
                      autofocus: true,
                      decoration: InputDecoration(
                        hintText: '이 채팅방에서 검색...',
                        isDense: true,
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                        suffixIcon: _searching
                            ? const Padding(padding: EdgeInsets.all(10), child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)))
                            : IconButton(icon: const Icon(Icons.search, size: 20), onPressed: () => _doSearch(_searchCtrl.text)),
                      ),
                      onSubmitted: _doSearch,
                    ),
                  ),
                  const SizedBox(width: 4),
                  IconButton(icon: const Icon(Icons.close, size: 20), onPressed: () => setState(() { _showSearch = false; _searchResults = []; _searchCtrl.clear(); })),
                ]),
                if (_searchResults.isNotEmpty)
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: 200),
                    child: ListView.builder(
                      shrinkWrap: true,
                      itemCount: _searchResults.length,
                      itemBuilder: (_, i) {
                        final r = _searchResults[i];
                        return ListTile(
                          dense: true,
                          title: Text(r.content, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13)),
                          subtitle: Text('${r.username} · ${r.timestamp.substring(0, 10)}', style: const TextStyle(fontSize: 11)),
                          onTap: () => setState(() {
                            _replyScrollTarget = r.effectiveId;
                            _showSearch = false;
                            _searchResults = [];
                            _searchCtrl.clear();
                          }),
                        );
                      },
                    ),
                  ),
              ],
            ),
          ),
        // Pin banner
        Builder(builder: (context) {
          final roomData = ref.watch(chatRoomsProvider).whenOrNull(
            data: (rooms) => rooms.where((r) => r.id == widget.roomId).firstOrNull,
          );
          if (roomData?.pinnedMessageId == null) return const SizedBox.shrink();
          final pinnedMsg = chatState.messages.where((m) => m.effectiveId == roomData!.pinnedMessageId).firstOrNull;
          if (pinnedMsg == null) return const SizedBox.shrink();
          return GestureDetector(
            onTap: () => setState(() => _replyScrollTarget = pinnedMsg.effectiveId),
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              color: Theme.of(context).colorScheme.primaryContainer.withAlpha(60),
              child: Row(
                children: [
                  const Icon(Icons.push_pin, size: 14),
                  const SizedBox(width: 8),
                  Expanded(child: Text(
                    pinnedMsg.content, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12),
                  )),
                  IconButton(
                    icon: const Icon(Icons.close, size: 14),
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
                    onPressed: () async {
                      try {
                        await ref.read(dioClientProvider).dio.delete('/api/chat/rooms/${widget.roomId}/pin');
                        ref.read(chatRoomsProvider.notifier).fetchRooms();
                      } catch (_) {}
                    },
                  ),
                ],
              ),
            ),
          );
        }),
        // Keyword alert banner
        Builder(builder: (ctx) {
          final alert = ref.watch(keywordAlertProvider);
          if (alert == null) return const SizedBox.shrink();
          return GestureDetector(
            onTap: () {
              ref.read(keywordAlertProvider.notifier).state = null;
              context.push('/chat/${alert.roomId}');
            },
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              color: Colors.amber.shade700,
              child: Row(
                children: [
                  const Icon(Icons.notifications_active, size: 16, color: Colors.white),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '${alert.roomName}: ${alert.snippet}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 13),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, size: 14, color: Colors.white),
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
                    onPressed: () => ref.read(keywordAlertProvider.notifier).state = null,
                  ),
                ],
              ),
            ),
          );
        }),
        // Offline / reconnecting banner (only after initial connection succeeded once)
        if (!chatState.isConnected && chatState.wasEverConnected && chatState.messages.isNotEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 16),
            color: Colors.orange.shade800,
            child: const Row(
              children: [
                SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                ),
                SizedBox(width: 8),
                Text('서버에 재연결 중...', style: TextStyle(color: Colors.white, fontSize: 13)),
              ],
            ),
          ),
        // Error state with retry button (only when messages could not be loaded)
        if (chatState.errorMessage != null && chatState.messages.isEmpty)
          Expanded(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.cloud_off, size: 48, color: Colors.grey),
                  const SizedBox(height: 12),
                  Text(chatState.errorMessage!),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: () => chatNotifier.joinRoom(widget.roomId),
                    icon: const Icon(Icons.refresh),
                    label: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          )
        else
          Expanded(
            child: ChatMessagesList(
              messages: chatState.messages,
              currentUsername: widget.username,
              isAiLoading: chatState.isAiLoading,
              isLoadingHistory: chatState.isLoadingHistory,
              hasMoreHistory: chatState.hasMoreHistory,
              onLoadMoreHistory: () => chatNotifier.loadMoreHistory(widget.roomId),
              readCounts: chatState.readCounts,
              scrollToMessageId: scrollTarget,
              highlightMessageId: widget.scrollToMessageId,
              onReplySelected: (msg) => chatNotifier.setReplyTarget(msg),
              onScrollToParentMessage: (parentId) =>
                  setState(() => _replyScrollTarget = parentId),
              onDeleteMessage: (messageId) =>
                  chatNotifier.deleteMessage(widget.roomId, messageId),
              onEditMessage: (messageId, currentContent) =>
                  _showEditDialog(context, ref, widget.roomId, messageId, currentContent),
              onReadCountTap: (messageId) =>
                  _showReadersSheet(context, ref, widget.roomId, messageId, chatState.messages),
              onReaction: (messageId, emoji) =>
                  chatNotifier.toggleReaction(widget.roomId, messageId, emoji),
              onForward: (msg) =>
                  _showForwardDialog(context, ref, chatNotifier, msg),
              onPin: (messageId) async {
                await ref.read(dioClientProvider).dio.put(
                  '/api/chat/rooms/${widget.roomId}/pin',
                  data: {'messageId': messageId},
                );
              },
              onRetry: (msg) => chatNotifier.retryFailedMessage(msg),
              onBookmarkToggle: (msg) {
                final notifier = ref.read(bookmarksProvider.notifier);
                if (notifier.isBookmarked(msg.effectiveId)) {
                  notifier.remove(msg.effectiveId);
                } else {
                  notifier.add(BookmarkEntry(
                    messageId: msg.effectiveId,
                    roomId: msg.chatRoomId,
                    username: msg.username,
                    content: msg.content,
                    timestamp: msg.timestamp,
                  ));
                }
              },
              bookmarkedMessageIds: ref.watch(bookmarksProvider)
                  .map((e) => e.messageId)
                  .toSet(),
              lastReadMessageId: chatState.lastReadMessageId,
            ),
          ),
        // Typing indicator with animated dots
        if (chatState.typingUsers.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  chatState.typingUsers.length == 1
                      ? '${chatState.typingUsers.first}님이 입력 중'
                      : '${chatState.typingUsers.join(", ")}님이 입력 중',
                  style: TextStyle(
                    fontSize: 12,
                    fontStyle: FontStyle.italic,
                    color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(160),
                  ),
                ),
                const _BouncingDots(),
              ],
            ),
          ),
        ChatInput(
          isConnected: chatState.isConnected,
          isAiLoading: chatState.isAiLoading,
          mutedUntil: ref.watch(mutedEventProvider(widget.roomId))?.mutedUntil,
          isHandoff: ref.watch(chatRoomsProvider).maybeWhen(
            data: (rooms) =>
                rooms.any((r) => r.id == widget.roomId && r.isHandoff),
            orElse: () => false,
          ),
          replyTarget: chatState.replyTarget,
          onCancelReply: () => chatNotifier.clearReplyTarget(),
          onTyping: () => chatNotifier.notifyTyping(widget.roomId),
          onMentionSearch: (query) => ref
              .read(chatNotifierProvider(widget.roomId).notifier)
              .searchParticipants(widget.roomId, query),
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
    ),
    );
  }

  void _showEditDialog(BuildContext context, WidgetRef ref, String roomId, String messageId, String currentContent) {
    final ctrl = TextEditingController(text: currentContent);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('메시지 수정'),
        content: TextField(
          controller: ctrl,
          maxLines: 5,
          minLines: 1,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: '수정할 내용을 입력하세요',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () async {
              final newContent = ctrl.text.trim();
              if (newContent.isEmpty) return;
              Navigator.of(ctx).pop();
              final ok = await ref
                  .read(chatNotifierProvider(roomId).notifier)
                  .editMessage(roomId, messageId, newContent);
              if (!ok && context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('메시지 수정에 실패했습니다.')),
                );
              }
            },
            child: const Text('수정'),
          ),
        ],
      ),
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
// Profile avatar — Image.network with errorBuilder to prevent white X-box
// ---------------------------------------------------------------------------
class _ProfileAvatar extends StatelessWidget {
  final String? url;
  final double radius;
  const _ProfileAvatar({required this.url, required this.radius});

  @override
  Widget build(BuildContext context) {
    return CircleAvatar(
      radius: radius,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
      child: ClipOval(
        child: (url != null && url!.isNotEmpty)
            ? Image.network(
                url!,
                width: radius * 2,
                height: radius * 2,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    Icon(Icons.person, size: radius),
              )
            : Icon(Icons.person, size: radius),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Invite member modal — search users and invite to room
// ---------------------------------------------------------------------------
class _InviteMemberModal extends ConsumerStatefulWidget {
  final String roomId;
  final int currentCount;

  const _InviteMemberModal({required this.roomId, required this.currentCount});

  @override
  ConsumerState<_InviteMemberModal> createState() => _InviteMemberModalState();
}

class _InviteMemberModalState extends ConsumerState<_InviteMemberModal> {
  final _searchCtrl = TextEditingController();
  List<Map<String, dynamic>> _results = [];
  bool _searching = false;
  String? _error;
  String? _inviting;
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _search(String query) async {
    if (query.trim().isEmpty) {
      setState(() { _results = []; _error = null; });
      return;
    }
    setState(() { _searching = true; _error = null; });
    try {
      final dio = ref.read(dioClientProvider).dio;
      final resp = await dio.get('/api/users/search', queryParameters: {'q': query.trim()});
      final data = resp.data;
      List<dynamic> list = [];
      if (data is Map && data['data'] is List) list = data['data'] as List;
      if (mounted) {
        setState(() {
          _results = list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
          _searching = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() { _searching = false; _error = '검색에 실패했습니다.'; });
    }
  }

  Future<void> _invite(Map<String, dynamic> user) async {
    if (widget.currentCount >= UIConstants.maxParticipants) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(ChatStrings.roomFull(UIConstants.maxParticipants))),
        );
      }
      return;
    }
    final username = user['username']?.toString() ?? '';
    setState(() => _inviting = username);
    try {
      final dio = ref.read(dioClientProvider).dio;
      await dio.post('/api/chat/rooms/${widget.roomId}/invite',
          data: {'targetUsername': username});
      if (mounted) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$username님을 초대했습니다.')),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() => _inviting = null);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('초대에 실패했습니다.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isFull = widget.currentCount >= UIConstants.maxParticipants;

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 36, height: 4,
                decoration: BoxDecoration(
                  color: cs.outline.withAlpha(80),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Icon(Icons.person_add_outlined, size: 20, color: cs.primary),
                const SizedBox(width: 8),
                Text('멤버 초대',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: cs.onSurface)),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: isFull ? cs.errorContainer : cs.surfaceContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    '${widget.currentCount}/${UIConstants.maxParticipants}명',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: isFull ? cs.onErrorContainer : cs.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
            if (isFull)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  '채팅방이 만석입니다. 멤버가 나간 후 초대할 수 있습니다.',
                  style: TextStyle(fontSize: 12, color: cs.error),
                ),
              ),
            const SizedBox(height: 12),
            TextField(
              controller: _searchCtrl,
              autofocus: true,
              decoration: InputDecoration(
                hintText: '사용자 이름 검색...',
                prefixIcon: const Icon(Icons.search, size: 20),
                suffixIcon: _searching
                    ? const Padding(
                        padding: EdgeInsets.all(12),
                        child: SizedBox(width: 16, height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2)))
                    : null,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                isDense: true,
              ),
              onChanged: (v) {
                _debounce?.cancel();
                _debounce = Timer(const Duration(milliseconds: 300), () => _search(v));
              },
            ),
            const SizedBox(height: 8),
            if (_error != null)
              Text(_error!, style: TextStyle(fontSize: 12, color: cs.error))
            else if (_results.isEmpty && _searchCtrl.text.isNotEmpty && !_searching)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Center(
                  child: Text('검색 결과가 없습니다.',
                      style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
                ),
              )
            else
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 280),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: _results.length,
                  itemBuilder: (_, i) {
                    final user = _results[i];
                    final name = user['username']?.toString() ?? '';
                    final isInviting = _inviting == name;
                    return ListTile(
                      dense: true,
                      leading: CircleAvatar(
                        radius: 18,
                        backgroundColor: AppColors.avatarPalette[
                            name.hashCode.abs() % AppColors.avatarPalette.length].withAlpha(180),
                        child: Text(
                          name.isNotEmpty ? name[0].toUpperCase() : '?',
                          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13),
                        ),
                      ),
                      title: Text(name, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
                      trailing: FilledButton(
                        onPressed: (isInviting || isFull)
                            ? null
                            : () => _invite(user),
                        style: FilledButton.styleFrom(
                          minimumSize: const Size(56, 32),
                          padding: const EdgeInsets.symmetric(horizontal: 14),
                        ),
                        child: isInviting
                            ? const SizedBox(width: 14, height: 14,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                            : const Text('초대', style: TextStyle(fontSize: 13)),
                      ),
                    );
                  },
                ),
              ),
          ],
        ),
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

// ---------------------------------------------------------------------------
// Animated typing dots ("···")
// ---------------------------------------------------------------------------
class _BouncingDots extends StatefulWidget {
  const _BouncingDots();
  @override
  State<_BouncingDots> createState() => _BouncingDotsState();
}

class _BouncingDotsState extends State<_BouncingDots> with TickerProviderStateMixin {
  late final List<AnimationController> _controllers;
  late final List<Animation<double>> _animations;

  @override
  void initState() {
    super.initState();
    _controllers = List.generate(3, (i) => AnimationController(
      vsync: this, duration: const Duration(milliseconds: 400),
    ));
    _animations = _controllers.map((c) =>
      Tween(begin: 0.0, end: -4.0).animate(CurvedAnimation(parent: c, curve: Curves.easeInOut)),
    ).toList();
    for (int i = 0; i < 3; i++) {
      Future.delayed(Duration(milliseconds: i * 150), () {
        if (mounted) _controllers[i].repeat(reverse: true);
      });
    }
  }

  @override
  void dispose() {
    for (final c in _controllers) c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(160);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(3, (i) => AnimatedBuilder(
        animation: _animations[i],
        builder: (_, child) => Transform.translate(
          offset: Offset(0, _animations[i].value),
          child: child,
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 1),
          child: Text('·', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900, color: color)),
        ),
      )),
    );
  }
}
