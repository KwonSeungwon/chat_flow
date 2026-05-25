import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants/chat_strings.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/theme_provider.dart';
import '../../core/utils/url_helper.dart';
import '../auth/auth_provider.dart';
import 'bookmark_provider.dart';
import 'chat_provider.dart';
import 'scheduled_messages_provider.dart';
import '../../shared/models/chat_message.dart';
import 'dialogs/bookmarks_dialog.dart';
import 'dialogs/change_password_dialog.dart';
import 'dialogs/edit_message_dialog.dart';
import 'dialogs/forward_dialog.dart';
import 'dialogs/in_room_search.dart';
import 'dialogs/profile_dialog.dart';
import 'dialogs/readers_sheet.dart';
import 'dialogs/room_settings_dialog.dart';
import 'widgets/ai_summary_button.dart';
import 'widgets/chat_room_sidebar.dart';
import 'widgets/connection_dot.dart';
import 'widgets/chat_messages_list.dart';
import 'widgets/chat_input.dart';
import 'widgets/lobby_placeholder.dart';
import 'widgets/participant_badge.dart';
import 'widgets/profile_avatar.dart';
import 'admin/widgets/room_members_sheet.dart';
import 'admin/widgets/moderator_queue_sheet.dart';
import 'admin/admin_event_listener.dart';
import 'admin/admin_event_state.dart';
import 'admin/current_room_role_provider.dart';
import '../../shared/models/room_role.dart';
import '../profile/widgets/profile_edit_dialog.dart';
import 'widgets/thread_panel.dart';
import 'widgets/edit_history_sheet.dart';

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
            if (effectiveRoomId != null)
              AiSummaryButton(roomId: effectiveRoomId),
            if (effectiveRoomId != null)
              IconButton(
                icon: const Icon(Icons.manage_search, size: 22),
                tooltip: '방 내 검색',
                onPressed: () => showInRoomSearch(context, ref, effectiveRoomId),
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
                    showRoomSettingsDialog(context, ref, effectiveRoomId, roomData);
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
                    showInRoomSearch(context, ref, effectiveRoomId);
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
            icon: ProfileAvatar(
              url: auth.profileImageUrl != null
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
                    ProfileAvatar(
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
                    if (context.mounted) showInRoomSearch(context, ref, roomId);
                  },
                ),
              ),
            ),
      body: Row(
        children: [
          if (isWide) ChatRoomSidebar(
            currentRoomId: effectiveRoomId ?? '',
            onSearchInRoom: (roomId) => showInRoomSearch(context, ref, roomId),
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
                    : const LobbyPlaceholder(),
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
                  showEditMessageDialog(context, ref, widget.roomId, messageId, currentContent),
              onViewEditHistory: (messageId, currentContent) =>
                  EditHistorySheet.show(context,
                      roomId: widget.roomId,
                      messageId: messageId,
                      currentContent: currentContent),
              onReadCountTap: (messageId) =>
                  showReadersSheet(context, ref, widget.roomId, messageId, chatState.messages),
              onReaction: (messageId, emoji) =>
                  chatNotifier.toggleReaction(widget.roomId, messageId, emoji),
              onForward: (msg) =>
                  showForwardDialog(context, ref, chatNotifier, msg),
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
              onOpenThread: (parent) => ThreadPanel.show(
                context,
                roomId: widget.roomId,
                parent: parent,
              ),
              replyCountFor: (id) =>
                  ref.read(chatNotifierProvider(widget.roomId).notifier).replyCountFor(id),
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
                const BouncingDots(),
              ],
            ),
          ),
        ChatInput(
          isConnected: chatState.isConnected,
          isAiLoading: chatState.isAiLoading,
          roomId: widget.roomId,
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
          onScheduleSend: (content, scheduledAt) async {
            await ref.read(scheduledMessagesProvider.notifier).schedule(
                  chatRoomId: widget.roomId,
                  content: content,
                  scheduledAt: scheduledAt,
                );
          },
        ),
      ],
    ),
    );
  }
}

