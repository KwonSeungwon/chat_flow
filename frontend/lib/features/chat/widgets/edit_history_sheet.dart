import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';

/// Modal bottom sheet showing the edit history of a single message.
/// Calls GET /api/chat/rooms/{roomId}/messages/{messageId}/edits and renders
/// the list newest-first (server already orders that way).
class EditHistorySheet extends ConsumerStatefulWidget {
  final String roomId;
  final String messageId;
  final String currentContent;

  const EditHistorySheet({
    super.key,
    required this.roomId,
    required this.messageId,
    required this.currentContent,
  });

  static Future<void> show(BuildContext context, {
    required String roomId,
    required String messageId,
    required String currentContent,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => EditHistorySheet(
        roomId: roomId,
        messageId: messageId,
        currentContent: currentContent,
      ),
    );
  }

  @override
  ConsumerState<EditHistorySheet> createState() => _EditHistorySheetState();
}

class _EditHistorySheetState extends ConsumerState<EditHistorySheet> {
  bool _loading = true;
  String? _error;
  List<_EditEntry> _history = [];

  @override
  void initState() {
    super.initState();
    _fetch();
  }

  Future<void> _fetch() async {
    try {
      final resp = await ref.read(dioClientProvider).dio.get(
            '/api/chat/rooms/${widget.roomId}/messages/${widget.messageId}/edits',
          );
      final data = (resp.data is Map) ? resp.data['data'] : resp.data;
      final list = (data is List)
          ? data.whereType<Map<String, dynamic>>().map(_EditEntry.fromJson).toList()
          : <_EditEntry>[];
      if (mounted) {
        setState(() {
          _history = list;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = '수정 이력을 불러오지 못했습니다';
          _loading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return DraggableScrollableSheet(
      initialChildSize: 0.7,
      minChildSize: 0.4,
      maxChildSize: 0.95,
      expand: false,
      builder: (_, scrollCtrl) {
        return Column(
          children: [
            Container(
              width: 40, height: 4,
              margin: const EdgeInsets.symmetric(vertical: 8),
              decoration: BoxDecoration(
                color: cs.outline.withAlpha(80),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Row(
                children: [
                  Icon(Icons.history, size: 18, color: cs.primary),
                  const SizedBox(width: 8),
                  Text('수정 이력 (${_history.length}회)',
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
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
                                    style: TextStyle(color: cs.onSurfaceVariant)),
                                const SizedBox(height: 12),
                                TextButton.icon(
                                  onPressed: () {
                                    setState(() {
                                      _loading = true;
                                      _error = null;
                                    });
                                    _fetch();
                                  },
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
                            _CurrentBlock(content: widget.currentContent),
                            const SizedBox(height: 12),
                            if (_history.isEmpty)
                              Padding(
                                padding: const EdgeInsets.symmetric(vertical: 24),
                                child: Center(
                                  child: Text('수정 이력이 없습니다',
                                      style: TextStyle(color: cs.onSurfaceVariant)),
                                ),
                              )
                            else
                              ..._history.map((e) => _PreviousBlock(entry: e)),
                          ],
                        ),
            ),
          ],
        );
      },
    );
  }
}

class _EditEntry {
  final String previousContent;
  final DateTime editedAt;
  const _EditEntry({required this.previousContent, required this.editedAt});

  factory _EditEntry.fromJson(Map<String, dynamic> j) {
    DateTime parse(dynamic v) {
      if (v is String) {
        try { return DateTime.parse(v).toLocal(); } catch (_) {}
      }
      return DateTime.now();
    }
    return _EditEntry(
      previousContent: j['previousContent']?.toString() ?? '',
      editedAt: parse(j['editedAt']),
    );
  }

  String get formattedTime {
    final dt = editedAt;
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')} '
        '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }
}

class _CurrentBlock extends StatelessWidget {
  final String content;
  const _CurrentBlock({required this.content});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.primaryContainer.withAlpha(40),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: cs.primary.withAlpha(80)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.bookmark, size: 14, color: cs.primary),
              const SizedBox(width: 4),
              Text('현재', style: TextStyle(
                  fontSize: 11, fontWeight: FontWeight.w700, color: cs.primary)),
            ],
          ),
          const SizedBox(height: 6),
          SelectableText(content),
        ],
      ),
    );
  }
}

class _PreviousBlock extends StatelessWidget {
  final _EditEntry entry;
  const _PreviousBlock({required this.entry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: cs.surfaceContainer,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: cs.outline.withAlpha(60)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(entry.formattedTime,
                style: TextStyle(
                    fontSize: 11,
                    color: cs.onSurfaceVariant,
                    fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            SelectableText(
              entry.previousContent,
              style: TextStyle(color: cs.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}
