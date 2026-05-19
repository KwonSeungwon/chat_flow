import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_message.dart';
import '../../auth/auth_provider.dart';
import '../chat_provider.dart' show chatNotifierProvider;
import 'chat_input.dart';
import 'edit_history_sheet.dart';

/// Modal sheet showing all replies to a parent message.
class ThreadPanel extends ConsumerStatefulWidget {
  final String roomId;
  final ChatMessage parent;

  const ThreadPanel({super.key, required this.roomId, required this.parent});

  static Future<void> show(
    BuildContext context, {
    required String roomId,
    required ChatMessage parent,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => ThreadPanel(roomId: roomId, parent: parent),
    );
  }

  @override
  ConsumerState<ThreadPanel> createState() => _ThreadPanelState();
}

class _ThreadPanelState extends ConsumerState<ThreadPanel> {
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _fetchReplies();
  }

  Future<void> _fetchReplies() async {
    final dio = ref.read(dioClientProvider).dio;
    try {
      final resp = await dio.get(
        '/api/chat/rooms/${widget.roomId}/messages/${widget.parent.effectiveId}/replies',
      );
      final raw = (resp.data is Map) ? resp.data['data'] : resp.data;
      if (raw is List) {
        final notifier =
            ref.read(chatNotifierProvider(widget.roomId).notifier);
        for (final entry in raw) {
          if (entry is Map<String, dynamic>) {
            notifier.mergeMessage(ChatMessage.fromJson(entry));
          }
        }
      }
    } catch (e) {
      // Localized message — do not leak the raw exception class to users.
      const msg = '네트워크 오류로 답글을 불러오지 못했습니다';
      if (mounted) setState(() => _error = msg);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _retry() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    await _fetchReplies();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final messages =
        ref.watch(chatNotifierProvider(widget.roomId)).messages;
    // Re-read the parent from current state so MESSAGE_DELETED / MESSAGE_EDITED
    // broadcasts arriving while the panel is open are reflected. Falls back to
    // the captured snapshot if the message is no longer in the buffer.
    final liveParent = messages
            .cast<ChatMessage?>()
            .firstWhere(
                (m) => m?.effectiveId == widget.parent.effectiveId,
                orElse: () => null) ??
        widget.parent;
    final replies = messages
        .where((m) =>
            m.parentMessageId == widget.parent.effectiveId && !m.deleted)
        .toList();
    final currentUsername = ref.watch(authProvider).username;

    return DraggableScrollableSheet(
      initialChildSize: 0.85,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      expand: false,
      builder: (_, scrollCtrl) {
        return Column(
          children: [
            Container(
              width: 40,
              height: 4,
              margin: const EdgeInsets.symmetric(vertical: 8),
              decoration: BoxDecoration(
                color: cs.outline.withAlpha(80),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Row(
                children: [
                  Icon(Icons.forum_outlined, size: 18, color: cs.primary),
                  const SizedBox(width: 8),
                  Text(
                    '답글 (${replies.length})',
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(_error!,
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                        color: cs.onSurfaceVariant)),
                                const SizedBox(height: 12),
                                TextButton.icon(
                                  onPressed: _retry,
                                  icon: const Icon(Icons.refresh, size: 16),
                                  label: const Text('다시 시도'),
                                ),
                              ],
                            ),
                          ),
                        )
                      : ListView(
                          controller: scrollCtrl,
                          padding: const EdgeInsets.all(12),
                          children: [
                            _ParentSummary(parent: liveParent),
                            const SizedBox(height: 12),
                            if (replies.isEmpty)
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                    vertical: 24),
                                child: Center(
                                  child: Text(
                                    '아직 답글이 없습니다',
                                    style: TextStyle(
                                        color: cs.onSurfaceVariant),
                                  ),
                                ),
                              )
                            else
                              ...replies.map((r) => _ReplyTile(
                                    msg: r,
                                    roomId: widget.roomId,
                                    isMine: r.username == currentUsername,
                                  )),
                          ],
                        ),
            ),
            const Divider(height: 1),
            Padding(
              padding: EdgeInsets.only(
                bottom: MediaQuery.of(context).viewInsets.bottom,
              ),
              child: ChatInput(
                roomId: widget.roomId,
                replyTarget: widget.parent,
                // Cancel closes the panel — the parent banner is the panel
                // itself; dismissing the reply means leaving the thread.
                onCancelReply: () => Navigator.of(context).pop(),
                onSend: (content, {String priority = 'ROUTINE'}) {
                  // replyOverride pins parentMessageId without mutating
                  // state.replyTarget (owned by the main chat input).
                  ref
                      .read(chatNotifierProvider(widget.roomId).notifier)
                      .sendMessage(
                        roomId: widget.roomId,
                        content: content,
                        priority: priority,
                        replyOverride: widget.parent,
                      );
                },
                isConnected: ref
                    .watch(chatNotifierProvider(widget.roomId))
                    .isConnected,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _ParentSummary extends StatelessWidget {
  final ChatMessage parent;
  const _ParentSummary({required this.parent});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isDeleted = parent.deleted;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.surfaceContainer,
        borderRadius: BorderRadius.circular(12),
        border: Border(left: BorderSide(color: cs.primary, width: 3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            parent.username,
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: cs.primary),
          ),
          const SizedBox(height: 4),
          Text(
            isDeleted ? '삭제된 메시지입니다.' : parent.content,
            maxLines: 4,
            overflow: TextOverflow.ellipsis,
            style: isDeleted
                ? TextStyle(
                    fontStyle: FontStyle.italic,
                    color: cs.onSurfaceVariant.withAlpha(160))
                : null,
          ),
          if (parent.edited && !isDeleted) ...[
            const SizedBox(height: 4),
            Text(
              '(수정됨)',
              style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
            ),
          ],
        ],
      ),
    );
  }
}

class _ReplyTile extends ConsumerWidget {
  final ChatMessage msg;
  final String roomId;
  final bool isMine;

  const _ReplyTile({
    required this.msg,
    required this.roomId,
    required this.isMine,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final hasActions = isMine && !msg.deleted;
    return GestureDetector(
      onLongPress: hasActions ? () => _openMenu(context, ref) : null,
      onSecondaryTapUp: hasActions
          ? (details) => _openMenu(context, ref, anchor: details.globalPosition)
          : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  msg.username,
                  style: const TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w700),
                ),
                const SizedBox(width: 8),
                Text(
                  _fmtTime(msg.timestamp),
                  style: TextStyle(
                      fontSize: 11, color: cs.onSurfaceVariant),
                ),
                if (msg.edited && !msg.deleted) ...[
                  const SizedBox(width: 6),
                  Text(
                    '(수정됨)',
                    style: TextStyle(
                        fontSize: 11, color: cs.onSurfaceVariant),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 2),
            Text(
              msg.deleted ? '삭제된 메시지입니다.' : msg.content,
              style: msg.deleted
                  ? TextStyle(
                      fontStyle: FontStyle.italic,
                      color: cs.onSurfaceVariant.withAlpha(160))
                  : null,
            ),
          ],
        ),
      ),
    );
  }

  void _openMenu(BuildContext context, WidgetRef ref, {Offset? anchor}) {
    final position = anchor ?? Offset.zero;
    final items = <PopupMenuEntry<String>>[
      const PopupMenuItem(
        value: 'copy',
        child: Row(children: [
          Icon(Icons.copy_outlined, size: 18),
          SizedBox(width: 8),
          Text('복사'),
        ]),
      ),
      const PopupMenuItem(
        value: 'edit',
        child: Row(children: [
          Icon(Icons.edit_outlined, size: 18),
          SizedBox(width: 8),
          Text('수정'),
        ]),
      ),
      if (msg.edited)
        const PopupMenuItem(
          value: 'history',
          child: Row(children: [
            Icon(Icons.history, size: 18),
            SizedBox(width: 8),
            Text('수정 이력'),
          ]),
        ),
      const PopupMenuItem(
        value: 'delete',
        child: Row(children: [
          Icon(Icons.delete_outline, size: 18, color: Colors.red),
          SizedBox(width: 8),
          Text('삭제', style: TextStyle(color: Colors.red)),
        ]),
      ),
    ];
    showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(
          position.dx, position.dy, position.dx, position.dy),
      items: items,
    ).then((value) {
      if (!context.mounted || value == null) return;
      switch (value) {
        case 'copy':
          Clipboard.setData(ClipboardData(text: msg.content));
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('메시지가 복사되었습니다.'),
              duration: Duration(seconds: 1),
            ),
          );
          break;
        case 'edit':
          _showEditDialog(context, ref);
          break;
        case 'history':
          EditHistorySheet.show(
            context,
            roomId: roomId,
            messageId: msg.effectiveId,
            currentContent: msg.content,
          );
          break;
        case 'delete':
          _confirmDelete(context, ref);
          break;
      }
    });
  }

  void _showEditDialog(BuildContext context, WidgetRef ref) {
    final ctrl = TextEditingController(text: msg.content);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('답글 수정'),
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
                  .editMessage(roomId, msg.effectiveId, newContent);
              if (!ok && context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('답글 수정에 실패했습니다.')),
                );
              }
            },
            child: const Text('수정'),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmDelete(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('답글 삭제'),
        content: const Text('이 답글을 삭제할까요? 되돌릴 수 없습니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    final ok = await ref
        .read(chatNotifierProvider(roomId).notifier)
        .deleteMessage(roomId, msg.effectiveId);
    if (!ok && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('답글 삭제에 실패했습니다.')),
      );
    }
  }

  String _fmtTime(String iso) {
    try {
      final dt = DateTime.parse(iso).toLocal();
      return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return '';
    }
  }
}
