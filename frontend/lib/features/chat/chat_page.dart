import 'dart:async';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/theme_provider.dart';
import '../auth/auth_provider.dart';
import 'chat_provider.dart';
import '../../shared/models/chat_message.dart';
import '../../shared/models/chat_room.dart';
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

void _showChangePasswordDialog(BuildContext context, WidgetRef ref) {
  final currentCtrl = TextEditingController();
  final newCtrl = TextEditingController();
  final confirmCtrl = TextEditingController();
  showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('비밀번호 변경'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(controller: currentCtrl, obscureText: true, decoration: const InputDecoration(labelText: '현재 비밀번호')),
          const SizedBox(height: 8),
          TextField(controller: newCtrl, obscureText: true, decoration: const InputDecoration(labelText: '새 비밀번호 (8자 이상)')),
          const SizedBox(height: 8),
          TextField(controller: confirmCtrl, obscureText: true, decoration: const InputDecoration(labelText: '새 비밀번호 확인')),
        ],
      ),
      actionsAlignment: MainAxisAlignment.center,
      actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
      actions: [
        Row(children: [
          Expanded(child: TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('취소'))),
          const SizedBox(width: 8),
          Expanded(child: FilledButton(
            onPressed: () async {
              if (newCtrl.text != confirmCtrl.text) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('새 비밀번호가 일치하지 않습니다.')));
                return;
              }
              if (newCtrl.text.length < 8) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('비밀번호는 8자 이상이어야 합니다.')));
                return;
              }
              try {
                await ref.read(dioClientProvider).dio.put('/api/auth/password', data: {
                  'currentPassword': currentCtrl.text,
                  'newPassword': newCtrl.text,
                });
                if (ctx.mounted) Navigator.of(ctx).pop();
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('비밀번호가 변경되었습니다.')));
                }
              } catch (_) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('비밀번호 변경에 실패했습니다. 현재 비밀번호를 확인해주세요.')));
                }
              }
            },
            child: const Text('변경'),
          )),
        ]),
      ],
    ),
  );
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
            url: auth.profileImageUrl != null ? _buildProfileUrl(auth.profileImageUrl!) : null,
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
              _showChangePasswordDialog(context, ref);
            },
          )),
        ]),
      ],
    ),
  );
}

void _showRoomSettingsDialog(BuildContext context, WidgetRef ref, String roomId, ChatRoom room) {
  final nameCtrl = TextEditingController(text: room.name);
  final descCtrl = TextEditingController(text: room.description ?? '');
  showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('채팅방 설정'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(controller: nameCtrl, decoration: const InputDecoration(labelText: '채팅방 이름')),
          const SizedBox(height: 8),
          TextField(controller: descCtrl, decoration: const InputDecoration(labelText: '설명'), maxLines: 3),
        ],
      ),
      actionsAlignment: MainAxisAlignment.center,
      actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
      actions: [
        Row(children: [
          Expanded(child: TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('취소'))),
          const SizedBox(width: 8),
          Expanded(child: FilledButton(
            onPressed: () async {
              try {
                await ref.read(dioClientProvider).dio.put('/api/chat/rooms/$roomId/settings', data: {
                  'name': nameCtrl.text.trim(),
                  'description': descCtrl.text.trim(),
                });
                if (ctx.mounted) Navigator.of(ctx).pop();
                ref.read(chatRoomsProvider.notifier).fetchRooms();
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('채팅방 설정이 변경되었습니다.')));
                }
              } catch (_) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('설정 변경에 실패했습니다.')));
                }
              }
            },
            child: const Text('저장'),
          )),
        ]),
      ],
    ),
  );
}

void _showForwardDialog(BuildContext context, WidgetRef ref, ChatNotifier currentNotifier, ChatMessage msg) {
  final rooms = ref.read(chatRoomsProvider).valueOrNull ?? [];
  showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('메시지 전달'),
      content: SizedBox(
        width: 280,
        height: 300,
        child: rooms.isEmpty
            ? const Center(child: Text('채팅방이 없습니다.'))
            : ListView.builder(
                itemCount: rooms.length,
                itemBuilder: (_, i) {
                  final room = rooms[i];
                  return ListTile(
                    leading: CircleAvatar(radius: 16, child: Text(room.name.isNotEmpty ? room.name[0].toUpperCase() : '#')),
                    title: Text(room.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                    onTap: () async {
                      final messenger = ScaffoldMessenger.of(context);
                      Navigator.of(ctx).pop();
                      final ok = await currentNotifier.forwardMessage(room.id, msg);
                      if (ok) {
                        messenger.showSnackBar(SnackBar(content: Text('"${room.name}"에 메시지를 전달했습니다.')));
                      } else {
                        messenger.showSnackBar(const SnackBar(content: Text('연결이 끊겨 전달에 실패했습니다.')));
                      }
                    },
                  );
                },
              ),
      ),
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
          if (effectiveRoomId != null && roomData != null)
            IconButton(
              icon: const Icon(Icons.settings_outlined, size: 20),
              tooltip: '채팅방 설정',
              onPressed: () => _showRoomSettingsDialog(context, ref, effectiveRoomId, roomData),
            ),
          if (effectiveRoomId != null)
            _AiSummaryButton(roomId: effectiveRoomId),
          IconButton(
            icon: const Icon(Icons.search),
            tooltip: '메시지 검색',
            onPressed: () => context.push('/search'),
          ),
          PopupMenuButton<String>(
            icon: _ProfileAvatar(
              url: auth.profileImageUrl != null
                  ? _buildProfileUrl(auth.profileImageUrl!)
                  : null,
              radius: 16,
            ),
            onSelected: (value) async {
              if (value == 'theme') {
                ref.read(themeModeProvider.notifier).state =
                    themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
              } else if (value == 'profile') {
                if (context.mounted) _showProfileDialog(context, ref);
              } else if (value == 'password') {
                if (context.mounted) _showChangePasswordDialog(context, ref);
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
                          ? _buildProfileUrl(auth.profileImageUrl!)
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
                value: 'profile',
                child: Row(
                  children: [
                    Icon(Icons.person_outline, size: 20),
                    SizedBox(width: 8),
                    Text('프로필 관리'),
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
                const Spacer(),
                TextButton.icon(
                  onPressed: () => showModalBottomSheet(
                    context: context,
                    isScrollControlled: true,
                    shape: const RoundedRectangleBorder(
                      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
                    ),
                    builder: (_) => _InviteMemberModal(
                      roomId: widget.roomId,
                      currentCount: _loading ? widget.count : _participants.length,
                    ),
                  ),
                  icon: const Icon(Icons.person_add_outlined, size: 16),
                  label: const Text('멤버 초대', style: TextStyle(fontSize: 13)),
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
          // Leave room button
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
            child: SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                icon: const Icon(Icons.exit_to_app, size: 18, color: Colors.red),
                label: const Text('채팅방 나가기', style: TextStyle(color: Colors.red)),
                style: OutlinedButton.styleFrom(
                  side: const BorderSide(color: Colors.red, width: 0.8),
                ),
                onPressed: () => _showLeaveConfirm(context, ref, widget.roomId),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _showLeaveConfirm(BuildContext context, WidgetRef ref, String roomId) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('채팅방 나가기'),
        content: const Text('채팅방에서 나가시겠습니까?\n언제든 다시 입장할 수 있습니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('취소'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              // Capture everything before pops — context/ref become invalid
              // after modal widgets are disposed.
              final router = GoRouter.of(context);
              final messenger = ScaffoldMessenger.of(context);
              final notifier = ref.read(chatNotifierProvider(roomId).notifier);
              Navigator.of(ctx).pop(); // close confirm dialog
              Navigator.of(context).pop(); // close participants modal
              final ok = await notifier.leaveRoom(roomId);
              if (ok) {
                router.go('/chat');
              } else {
                messenger.showSnackBar(
                  const SnackBar(content: Text('채팅방 나가기에 실패했습니다.')),
                );
              }
            },
            child: const Text('나가기'),
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
      ref.read(chatNotifierProvider(widget.roomId).notifier)
          .markRoomRead(widget.roomId);
    });
  }

  @override
  void dispose() {
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
  }

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(chatNotifierProvider(widget.roomId));
    final chatNotifier = ref.read(chatNotifierProvider(widget.roomId).notifier);

    // Route away when the room is deleted server-side
    ref.listen(chatNotifierProvider(widget.roomId), (_, next) {
      if (next.roomDeleted && context.mounted) {
        context.go('/chat');
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
          isHandoff: ref.watch(chatRoomsProvider).maybeWhen(
            data: (rooms) =>
                rooms.any((r) => r.id == widget.roomId && r.isHandoff),
            orElse: () => false,
          ),
          replyTarget: chatState.replyTarget,
          onCancelReply: () => chatNotifier.clearReplyTarget(),
          onTyping: () => chatNotifier.notifyTyping(widget.roomId),
          onMentionSearch: (query) async {
            try {
              final resp = await ref.read(dioClientProvider).dio.get('/api/users/search', queryParameters: {'q': query});
              final data = resp.data;
              if (data is Map && data['data'] is List) {
                return (data['data'] as List).cast<Map<String, dynamic>>();
              }
            } catch (_) {}
            return [];
          },
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
  static const int _maxParticipants = 10;
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
    if (widget.currentCount >= _maxParticipants) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('채팅방이 만석입니다 (최대 10명).')),
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
    final isFull = widget.currentCount >= _maxParticipants;

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
                    '${widget.currentCount}/$_maxParticipants명',
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
