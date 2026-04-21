import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../../../core/network/dio_client.dart';

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
  List<Map<String, dynamic>> _results = [];
  bool _loading = false;
  String? _error;

  Future<void> _search() async {
    final q = _queryCtrl.text.trim();
    if (q.isEmpty &&
        _userCtrl.text.trim().isEmpty &&
        _start == null &&
        _end == null) {
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioClientProvider).dio;
      late final Map<String, dynamic> data;
      // Priority: time-range > users > messages
      if (_start != null && _end != null) {
        final resp = await dio.get(
          '/api/search/rooms/${widget.roomId}/time-range',
          queryParameters: {
            'start': _start!.toUtc().toIso8601String(),
            'end': _end!.toUtc().toIso8601String(),
            if (q.isNotEmpty) 'query': q,
            if (_userCtrl.text.trim().isNotEmpty)
              'username': _userCtrl.text.trim(),
            'size': 50,
          },
        );
        data = resp.data as Map<String, dynamic>;
      } else if (_userCtrl.text.trim().isNotEmpty) {
        final resp = await dio.get(
          '/api/search/rooms/${widget.roomId}/users',
          queryParameters: {
            'username': _userCtrl.text.trim(),
            if (q.isNotEmpty) 'query': q,
            'size': 50,
          },
        );
        data = resp.data as Map<String, dynamic>;
      } else {
        final resp = await dio.get(
          '/api/search/rooms/${widget.roomId}/messages',
          queryParameters: {'query': q, 'size': 50},
        );
        data = resp.data as Map<String, dynamic>;
      }
      final raw = data['data'] ?? data['content'] ?? [];
      final hits =
          raw is Map ? (raw['content'] as List? ?? []) : (raw as List? ?? []);
      setState(() {
        _results = hits.cast<Map<String, dynamic>>();
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = '검색 실패: $e';
        _loading = false;
      });
    }
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
    if (picked != null) {
      setState(() {
        if (isStart) {
          _start = DateTime(picked.year, picked.month, picked.day, 0, 0);
        } else {
          _end = DateTime(picked.year, picked.month, picked.day, 23, 59, 59);
        }
      });
    }
  }

  @override
  void dispose() {
    _queryCtrl.dispose();
    _userCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final df = DateFormat('yyyy-MM-dd');
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        constraints: const BoxConstraints(maxHeight: 600),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            // Header
            Row(children: [
              Icon(Icons.search, size: 20, color: colorScheme.primary),
              const SizedBox(width: 8),
              const Text('방 내 검색',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              const Spacer(),
              IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.of(context).pop()),
            ]),
            const SizedBox(height: 8),
            // Query field
            TextField(
              controller: _queryCtrl,
              autofocus: true,
              decoration: InputDecoration(
                hintText: '검색어',
                prefixIcon: const Icon(Icons.search, size: 20),
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8)),
                isDense: true,
              ),
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
            ),
            const SizedBox(height: 8),
            // Sender filter
            TextField(
              controller: _userCtrl,
              decoration: InputDecoration(
                hintText: '발신자(선택)',
                prefixIcon: const Icon(Icons.person_outline, size: 20),
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8)),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            // Date range
            Row(children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.date_range, size: 16),
                  label: Text(_start != null ? df.format(_start!) : '시작일'),
                  onPressed: () => _pickDate(true),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.date_range, size: 16),
                  label: Text(_end != null ? df.format(_end!) : '종료일'),
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
            // Search button
            FilledButton.icon(
              icon: const Icon(Icons.search),
              label: const Text('검색'),
              onPressed: _loading ? null : _search,
            ),
            const SizedBox(height: 12),
            // Results
            if (_loading)
              const Padding(
                  padding: EdgeInsets.all(16),
                  child: Center(child: CircularProgressIndicator())),
            if (_error != null)
              Text(_error!,
                  style: const TextStyle(color: Colors.red, fontSize: 12)),
            if (!_loading && _results.isEmpty && _error == null)
              const Padding(
                  padding: EdgeInsets.all(16),
                  child: Center(
                      child: Text('검색 결과 없음',
                          style: TextStyle(color: Colors.grey)))),
            Flexible(
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: _results.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (ctx, i) {
                  final r = _results[i];
                  final content = r['content']?.toString() ?? '';
                  final username = r['username']?.toString() ?? '';
                  final ts = r['timestamp']?.toString() ?? '';
                  final msgId = r['messageId']?.toString() ??
                      r['id']?.toString() ??
                      '';
                  return ListTile(
                    dense: true,
                    title: Text(content,
                        maxLines: 2, overflow: TextOverflow.ellipsis),
                    subtitle: Text('$username · $ts',
                        style: const TextStyle(fontSize: 11)),
                    onTap: () {
                      if (msgId.isNotEmpty && widget.onResultTap != null) {
                        widget.onResultTap!(msgId);
                      }
                    },
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
