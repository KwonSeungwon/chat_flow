import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'admin_game_api.dart';
import 'admin_game_provider.dart';

const _tabs = [
  ('DRAFT', '검수 대기'),
  ('APPROVED', '승인됨'),
  ('REJECTED', '반려됨'),
];

class QuestionReviewPage extends StatelessWidget {
  const QuestionReviewPage({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: _tabs.length,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('문제 검수'),
          bottom: TabBar(tabs: [for (final t in _tabs) Tab(text: t.$2)]),
        ),
        body: TabBarView(
          children: [for (final t in _tabs) _QuestionList(status: t.$1)],
        ),
      ),
    );
  }
}

class _QuestionList extends ConsumerStatefulWidget {
  final String status;
  const _QuestionList({required this.status});

  @override
  ConsumerState<_QuestionList> createState() => _QuestionListState();
}

class _QuestionListState extends ConsumerState<_QuestionList>
    with AutomaticKeepAliveClientMixin {
  static const _pageSize = 20;
  final List<GameQuestion> _items = [];
  int _total = 0;
  int _page = 0;
  bool _loading = false;
  bool _forbidden = false;
  String? _error;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final res = await ref
          .read(adminGameApiProvider)
          .listQuestions(status: widget.status, page: _page, size: _pageSize);
      if (!mounted) return;
      setState(() {
        _items.addAll(res.items);
        _total = res.total;
        _page++;
      });
    } on AdminGameApiException catch (e) {
      if (!mounted) return;
      setState(() {
        if (e.isForbidden) {
          _forbidden = true;
        } else {
          _error = e.message;
        }
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _snack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  /// Returns true on success.
  Future<bool> _update(
    GameQuestion q, {
    String? stem,
    List<String>? choices,
    int? answerIndex,
    String? explanation,
    String? status,
  }) async {
    try {
      final updated = await ref.read(adminGameApiProvider).updateQuestion(
            q.id,
            stem: stem,
            choices: choices,
            answerIndex: answerIndex,
            explanation: explanation,
            status: status,
          );
      if (!mounted) return true;
      setState(() {
        final i = _items.indexWhere((e) => e.id == q.id);
        if (i < 0) return;
        if (updated.status == widget.status) {
          _items[i] = updated;
        } else {
          _items.removeAt(i);
          if (_total > 0) _total--;
        }
      });
      return true;
    } on AdminGameApiException catch (e) {
      if (!mounted) return false;
      _snack(e.isDuplicate ? '이미 같은 문제가 있습니다.' : e.message);
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    if (_forbidden) {
      return const Center(child: Text('관리자만 볼 수 있습니다'));
    }
    if (_items.isEmpty) {
      if (_loading) return const Center(child: CircularProgressIndicator());
      if (_error != null) {
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!),
              TextButton(onPressed: _load, child: const Text('다시 시도')),
            ],
          ),
        );
      }
      return const Center(child: Text('문제가 없습니다'));
    }
    final hasMore = _items.length < _total;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        for (final q in _items)
          _QuestionCard(
            key: ValueKey(q.id),
            question: q,
            onSetStatus: (s) => _update(q, status: s),
            onSave: (stem, choices, answer, expl) => _update(q,
                stem: stem,
                choices: choices,
                answerIndex: answer,
                explanation: expl),
          ),
        if (hasMore)
          Center(
            child: _loading
                ? const Padding(
                    padding: EdgeInsets.all(8),
                    child: CircularProgressIndicator())
                : TextButton(onPressed: _load, child: const Text('더 보기')),
          ),
      ],
    );
  }
}

class _QuestionCard extends StatefulWidget {
  final GameQuestion question;
  final Future<bool> Function(String status) onSetStatus;
  final Future<bool> Function(
      String stem, List<String> choices, int answer, String explanation) onSave;

  const _QuestionCard({
    super.key,
    required this.question,
    required this.onSetStatus,
    required this.onSave,
  });

  @override
  State<_QuestionCard> createState() => _QuestionCardState();
}

class _QuestionCardState extends State<_QuestionCard> {
  bool _editing = false;
  bool _busy = false;
  late final TextEditingController _stem;
  late final TextEditingController _explanation;
  late final List<TextEditingController> _choices;
  int _answer = 0;

  @override
  void initState() {
    super.initState();
    final q = widget.question;
    _stem = TextEditingController(text: q.stem);
    _explanation = TextEditingController(text: q.explanation);
    _choices = [for (final c in q.choices) TextEditingController(text: c)];
    _answer = q.answerIndex;
  }

  @override
  void dispose() {
    _stem.dispose();
    _explanation.dispose();
    for (final c in _choices) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _run(Future<bool> Function() action,
      {bool closeEditOnSuccess = false}) async {
    setState(() => _busy = true);
    final ok = await action();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (ok && closeEditOnSuccess) _editing = false;
    });
  }

  void _startEdit() {
    final q = widget.question;
    _stem.text = q.stem;
    _explanation.text = q.explanation;
    for (var i = 0; i < _choices.length; i++) {
      _choices[i].text = q.choices[i];
    }
    setState(() {
      _answer = q.answerIndex;
      _editing = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.question;
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${q.dungeonId} · ${q.topicTitle}',
                style: theme.textTheme.labelMedium),
            const SizedBox(height: 8),
            if (_editing) _buildEditor() else _buildView(q, theme),
            const SizedBox(height: 8),
            if (!_editing)
              Wrap(
                spacing: 8,
                children: [
                  if (q.status != 'APPROVED')
                    FilledButton(
                      onPressed: _busy
                          ? null
                          : () => _run(() => widget.onSetStatus('APPROVED')),
                      child: const Text('승인'),
                    ),
                  if (q.status != 'REJECTED')
                    OutlinedButton(
                      onPressed: _busy
                          ? null
                          : () => _run(() => widget.onSetStatus('REJECTED')),
                      child: const Text('반려'),
                    ),
                  TextButton(
                    onPressed: _busy ? null : _startEdit,
                    child: const Text('수정'),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildView(GameQuestion q, ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(q.stem, style: theme.textTheme.titleMedium),
        const SizedBox(height: 8),
        for (var i = 0; i < q.choices.length; i++)
          Container(
            width: double.infinity,
            margin: const EdgeInsets.only(bottom: 4),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: i == q.answerIndex
                  ? theme.colorScheme.primaryContainer
                  : null,
              border: Border.all(color: theme.dividerColor),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              children: [
                Expanded(child: Text('${i + 1}. ${q.choices[i]}')),
                if (i == q.answerIndex)
                  const Icon(Icons.check_circle, size: 18),
              ],
            ),
          ),
        const SizedBox(height: 4),
        Text('해설: ${q.explanation}', style: theme.textTheme.bodySmall),
      ],
    );
  }

  Widget _buildEditor() {
    return Column(
      children: [
        TextField(
          controller: _stem,
          decoration: const InputDecoration(labelText: '문제'),
          maxLines: null,
        ),
        RadioGroup<int>(
          groupValue: _answer,
          onChanged: (v) => setState(() => _answer = v ?? _answer),
          child: Column(
            children: [
              for (var i = 0; i < _choices.length; i++)
                Row(
                  children: [
                    Radio<int>(value: i),
                    Expanded(
                      child: TextField(
                        controller: _choices[i],
                        decoration: InputDecoration(labelText: '보기 ${i + 1}'),
                      ),
                    ),
                  ],
                ),
            ],
          ),
        ),
        TextField(
          controller: _explanation,
          decoration: const InputDecoration(labelText: '해설'),
          maxLines: null,
        ),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            TextButton(
              onPressed: _busy ? null : () => setState(() => _editing = false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: _busy
                  ? null
                  : () => _run(
                        () => widget.onSave(
                          _stem.text.trim(),
                          [for (final c in _choices) c.text.trim()],
                          _answer,
                          _explanation.text.trim(),
                        ),
                        closeEditOnSuccess: true,
                      ),
              child: const Text('저장'),
            ),
          ],
        ),
      ],
    );
  }
}
