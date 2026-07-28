import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_message.dart';
import '../../auth/auth_provider.dart';
import '../bookmark_provider.dart';
import '../chat_provider.dart';
import '../scheduled_messages_provider.dart';
import '../admin/admin_event_state.dart';
import '../dialogs/edit_message_dialog.dart';
import '../dialogs/forward_dialog.dart';
import '../dialogs/readers_sheet.dart';
import 'chat_input.dart';
import 'chat_messages_list.dart';
import 'connection_dot.dart';
import 'edit_history_sheet.dart';
import 'thread_panel.dart';

// ---------------------------------------------------------------------------
// Active chat room content (messages + input)
// ---------------------------------------------------------------------------
class ChatRoomContent extends ConsumerStatefulWidget {
  final String roomId;
  final String username;
  final String? scrollToMessageId;

  const ChatRoomContent({
    super.key,
    required this.roomId,
    required this.username,
    this.scrollToMessageId,
  });

  @override
  ConsumerState<ChatRoomContent> createState() => ChatRoomContentState();
}

class ChatRoomContentState extends ConsumerState<ChatRoomContent> {
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
  void didUpdateWidget(ChatRoomContent oldWidget) {
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

    // Surface transient errors (e.g. reaction failures) via SnackBar
    ref.listen<ChatMessagesState>(chatNotifierProvider(widget.roomId), (prev, next) {
      if (!context.mounted) return;
      final msg = next.errorMessage;
      if (msg != null && msg != prev?.errorMessage) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(msg)),
        );
        Future.microtask(() =>
          ref.read(chatNotifierProvider(widget.roomId).notifier).clearError());
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
          onEditLastMessage: () {
            final myUserId = ref.read(authProvider).userId;
            // 빈 문자열 가드 — fromJson은 userId 결손 시 '' 폴백이라, 가드 없으면
            // 타인 메시지(userId='')가 내 것으로 매칭될 수 있다 (리뷰 지적).
            if (myUserId == null || myUserId.isEmpty) return;
            final lastOwn = chatState.messages
                .where((m) =>
                    !m.deleted &&
                    m.type == 'CHAT' &&
                    m.userId == myUserId)
                .lastOrNull;
            if (lastOwn == null) return;
            showEditMessageDialog(
              context, ref, widget.roomId,
              lastOwn.effectiveId, lastOwn.content,
            );
          },
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
              .searchMentionCandidates(widget.roomId, query),
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
