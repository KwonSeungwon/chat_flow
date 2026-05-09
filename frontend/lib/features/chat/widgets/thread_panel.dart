import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import '../../../shared/models/chat_message.dart';
import '../chat_provider.dart' show chatNotifierProvider;
import 'chat_input.dart';

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
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final messages =
        ref.watch(chatNotifierProvider(widget.roomId)).messages;
    final replies = messages
        .where((m) =>
            m.parentMessageId == widget.parent.effectiveId && !m.deleted)
        .toList();

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
                            child:
                                Text('답글 불러오기 실패: $_error'),
                          ),
                        )
                      : ListView(
                          controller: scrollCtrl,
                          padding: const EdgeInsets.all(12),
                          children: [
                            _ParentSummary(parent: widget.parent),
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
                              ...replies.map((r) => _ReplyTile(msg: r)),
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
                onCancelReply: () {},
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
                isConnected: true,
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
          Text(parent.content,
              maxLines: 4, overflow: TextOverflow.ellipsis),
        ],
      ),
    );
  }
}

class _ReplyTile extends StatelessWidget {
  final ChatMessage msg;
  const _ReplyTile({required this.msg});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
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
            ],
          ),
          const SizedBox(height: 2),
          Text(msg.content),
        ],
      ),
    );
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
