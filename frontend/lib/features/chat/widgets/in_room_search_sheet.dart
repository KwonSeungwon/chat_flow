import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../in_room_search_provider.dart';

class InRoomSearchSheet extends ConsumerStatefulWidget {
  final String roomId;
  final void Function(String messageId)? onResultTap;

  const InRoomSearchSheet({super.key, required this.roomId, this.onResultTap});

  @override
  ConsumerState<InRoomSearchSheet> createState() => _InRoomSearchSheetState();
}

class _InRoomSearchSheetState extends ConsumerState<InRoomSearchSheet> {
  final _queryCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  DateTime? _start;
  DateTime? _end;

  static const _typeOptions = [
    ('일반', 'CHAT'),
    ('파일', 'FILE'),
    ('AI 요약', 'AI_SUMMARY'),
  ];

  @override
  void dispose() {
    _queryCtrl.dispose();
    _userCtrl.dispose();
    super.dispose();
  }

  void _search() {
    ref.read(inRoomSearchProvider(widget.roomId).notifier).search(
          query: _queryCtrl.text,
          username: _userCtrl.text,
          startDate: _start,
          endDate: _end,
        );
    FocusScope.of(context).unfocus();
  }

  Future<void> _pickDate(bool isStart) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: isStart
          ? (_start ?? now.subtract(const Duration(days: 7)))
          : (_end ?? now),
      firstDate: DateTime(2020),
      lastDate: now.add(const Duration(days: 1)),
    );
    if (!mounted || picked == null) return;
    setState(() {
      if (isStart) {
        _start = DateTime(picked.year, picked.month, picked.day, 0, 0);
      } else {
        _end = DateTime(picked.year, picked.month, picked.day, 23, 59, 59);
      }
    });
  }

  Widget _highlightedText(String text, String query) {
    if (query.isEmpty) {
      return Text(text, maxLines: 2, overflow: TextOverflow.ellipsis);
    }
    final lower = text.toLowerCase();
    final lowerQ = query.toLowerCase();
    final spans = <TextSpan>[];
    int start = 0;
    while (true) {
      final idx = lower.indexOf(lowerQ, start);
      if (idx == -1) {
        spans.add(TextSpan(text: text.substring(start)));
        break;
      }
      if (idx > start) spans.add(TextSpan(text: text.substring(start, idx)));
      spans.add(TextSpan(
        text: text.substring(idx, idx + lowerQ.length),
        style: TextStyle(
          backgroundColor: Theme.of(context).colorScheme.primaryContainer,
          fontWeight: FontWeight.bold,
        ),
      ));
      start = idx + lowerQ.length;
    }
    return RichText(
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      text: TextSpan(
        style: DefaultTextStyle.of(context).style.copyWith(fontSize: 13),
        children: spans,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final df = DateFormat('MM/dd');
    final state = ref.watch(inRoomSearchProvider(widget.roomId));

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Container(
        constraints: const BoxConstraints(maxHeight: 620),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(children: [
              Icon(Icons.search, size: 20, color: cs.primary),
              const SizedBox(width: 8),
              const Text('방 내 검색',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              const Spacer(),
              IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.of(context).pop()),
            ]),
            const SizedBox(height: 8),
            TextField(
              controller: _queryCtrl,
              autofocus: true,
              decoration: InputDecoration(
                hintText: '검색어 (선택)',
                prefixIcon: const Icon(Icons.search, size: 20),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                isDense: true,
              ),
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('sender_field'),
              controller: _userCtrl,
              decoration: InputDecoration(
                hintText: '발신자 (선택)',
                prefixIcon: const Icon(Icons.person_outline, size: 20),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.date_range, size: 16),
                  label: Text(_start != null ? df.format(_start!) : '시작일',
                      style: const TextStyle(fontSize: 13)),
                  onPressed: () => _pickDate(true),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.date_range, size: 16),
                  label: Text(_end != null ? df.format(_end!) : '종료일',
                      style: const TextStyle(fontSize: 13)),
                  onPressed: () => _pickDate(false),
                ),
              ),
              if (_start != null || _end != null)
                IconButton(
                  icon: const Icon(Icons.clear, size: 18),
                  onPressed: () => setState(() {
                    _start = null;
                    _end = null;
                  }),
                ),
            ]),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: _typeOptions.map((opt) {
                final (label, value) = opt;
                final selected = state.messageTypeFilter == value;
                return ChoiceChip(
                  label: Text(label),
                  selected: selected,
                  onSelected: (_) => ref
                      .read(inRoomSearchProvider(widget.roomId).notifier)
                      .setMessageTypeFilter(value),
                );
              }).toList(),
            ),
            const SizedBox(height: 8),
            FilledButton.icon(
              icon: const Icon(Icons.search),
              label: const Text('검색'),
              onPressed: state.isLoading ? null : _search,
            ),
            const SizedBox(height: 8),
            if (state.isLoading)
              const Center(child: CircularProgressIndicator()),
            if (state.error != null)
              Text(state.error!,
                  style: const TextStyle(color: Colors.red, fontSize: 12)),
            if (!state.isLoading && state.hasSearched && state.results.isEmpty)
              const Center(
                child: Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Text('검색 결과 없음',
                      style: TextStyle(color: Colors.grey)),
                ),
              ),
            if (!state.isLoading && state.results.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text('${state.total}개 결과',
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
              ),
              Flexible(
                child: ListView.separated(
                  shrinkWrap: true,
                  itemCount: state.results.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (ctx, i) {
                    final msg = state.results[i];
                    final ts = _formatTs(msg.timestamp);
                    return ListTile(
                      dense: true,
                      leading: CircleAvatar(
                        radius: 16,
                        backgroundColor: cs.primaryContainer,
                        child: Text(
                          msg.username.isNotEmpty
                              ? msg.username[0].toUpperCase()
                              : '?',
                          style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: cs.onPrimaryContainer),
                        ),
                      ),
                      title: Row(children: [
                        Text(msg.username,
                            style: const TextStyle(
                                fontWeight: FontWeight.w600, fontSize: 12)),
                        const SizedBox(width: 6),
                        Text(ts,
                            style: TextStyle(
                                fontSize: 10, color: cs.onSurfaceVariant)),
                      ]),
                      subtitle: Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: _highlightedText(
                            msg.content, _queryCtrl.text.trim()),
                      ),
                      onTap: () {
                        Navigator.of(context).pop();
                        if (widget.onResultTap != null) {
                          widget.onResultTap!(msg.effectiveId);
                        }
                      },
                    );
                  },
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatTs(String ts) {
    try {
      final dt = DateTime.parse(ts).toLocal();
      return DateFormat('MM/dd HH:mm').format(dt);
    } catch (_) {
      return '';
    }
  }
}
