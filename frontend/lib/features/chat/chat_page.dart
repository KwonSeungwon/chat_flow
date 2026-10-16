import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants/chat_strings.dart';
import '../../core/network/api_response.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/theme_provider.dart';
import '../../core/utils/url_helper.dart';
import '../auth/auth_provider.dart';
import 'chat_provider.dart';
import 'dialogs/bookmarks_dialog.dart';
import 'dialogs/change_password_dialog.dart';
import 'dialogs/profile_dialog.dart';
import 'dialogs/room_settings_dialog.dart';
import 'widgets/chat_room_content.dart';
import 'widgets/chat_room_sidebar.dart';
import 'widgets/connection_dot.dart';
import 'widgets/lobby_placeholder.dart';
import 'widgets/participant_badge.dart';
import '../../shared/widgets/user_avatar.dart';
import 'admin/widgets/room_members_sheet.dart';
import 'admin/widgets/moderator_queue_sheet.dart';
import 'admin/admin_event_listener.dart';
import 'admin/current_room_role_provider.dart';
import '../../shared/models/room_role.dart';
import '../profile/widgets/profile_edit_dialog.dart';

Future<void> _copyInviteLink(BuildContext context, WidgetRef ref, String roomId) async {
  try {
    final dio = ref.read(dioClientProvider).dio;
    final resp = await dio.post('/api/chat/rooms/$roomId/invite-link');
    final data = resp.data;
    final url = apiResponseMap(data)?['url']?.toString();
    if (url == null || url.isEmpty) throw Exception('url empty');
    await Clipboard.setData(ClipboardData(text: url));
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: const Text(ChatStrings.inviteLinkCopied),
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

class ChatPage extends ConsumerWidget {
  final String? roomId;
  final String? scrollToMessageId;

  const ChatPage({super.key, this.roomId, this.scrollToMessageId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);

    // Signed out (401, expiry, logout): the router's auth redirect moves us
    // to /login, keeping ?redirect= when the session died. Navigating from
    // here too would race it and drop the redirect.
    if (!auth.isAuthenticated) {
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
    final roomDisplayName = roomData?.name ?? effectiveRoomId ?? 'Gossiplog';

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
              ConnectionDot(
                connected: ref.watch(chatNotifierProvider(effectiveRoomId)).isConnected,
              ),
              // Participant badge only on wide screens to save AppBar space
              if (isWide && roomData != null) ...[
                const SizedBox(width: 10),
                Builder(builder: (context) {
                  final realtimeCount = ref.watch(
                    chatNotifierProvider(effectiveRoomId).select((s) => s.participantCount),
                  );
                  return ParticipantBadge(
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
            IconButton(
              icon: const Icon(Icons.public, size: 20),
              tooltip: '월드',
              onPressed: () => context.push('/world'),
            ),
            if (effectiveRoomId != null && roomData != null)
              IconButton(
                icon: const Icon(Icons.settings_outlined, size: 20),
                tooltip: '채팅방 설정',
                onPressed: () => showRoomSettingsDialog(context, ref, effectiveRoomId, roomData),
              ),
            if (effectiveRoomId != null)
              IconButton(
                icon: const Icon(Icons.person_add_outlined, size: 20),
                tooltip: '초대 링크 복사',
                onPressed: () => _copyInviteLink(context, ref, effectiveRoomId),
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
                    showRoomSettingsDialog(context, ref, effectiveRoomId, roomData);
                  } else if (value == 'participants') {
                    showRoomMembersSheet(context, effectiveRoomId);
                  } else if (value == 'world') {
                    context.push('/world');
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
                    value: 'world',
                    child: Row(
                      children: [
                        Icon(Icons.public, size: 20),
                        SizedBox(width: 8),
                        Text('월드'),
                      ],
                    ),
                  ),
                ],
              ),
          ],
          PopupMenuButton<String>(
            icon: UserAvatar(
              fallbackName: auth.username,
              imageUrl: auth.profileImageUrl != null
                  ? buildFullUrl(auth.profileImageUrl!)
                  : null,
              radius: 16,
            ),
            onSelected: (value) async {
              if (value == 'theme') {
                ref.read(themeModeProvider.notifier).toggle();
              } else if (value == 'profile') {
                if (context.mounted) showProfileDialog(context, ref);
              } else if (value == 'profile_edit') {
                if (context.mounted) showProfileEditDialog(context);
              } else if (value == 'bookmarks') {
                if (context.mounted) showBookmarksDialog(context, ref);
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
                    UserAvatar(
                      fallbackName: auth.username,
                      imageUrl: auth.profileImageUrl != null
                          ? buildFullUrl(auth.profileImageUrl!)
                          : null,
                      radius: 30,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      auth.username.isNotEmpty ? auth.username : '사용자',
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
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
                ),
              ),
            ),
      body: Row(
        children: [
          if (isWide) ChatRoomSidebar(
            currentRoomId: effectiveRoomId ?? '',
          ),
          if (isWide) const VerticalDivider(width: 1, thickness: 1),
          Expanded(
            // 채팅방 배경(라이트: 하늘→살구 세로 그라데이션)은 1100 제한 바깥까지 채운다.
            child: DecoratedBox(
              decoration: BoxDecoration(gradient: ChatPalette.of(context).roomBackground),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1100),
                  child: effectiveRoomId != null
                      ? ChatRoomContent(
                          roomId: effectiveRoomId,
                          username: auth.username,
                          scrollToMessageId: scrollToMessageId,
                        )
                      : const LobbyPlaceholder(),
                ),
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

