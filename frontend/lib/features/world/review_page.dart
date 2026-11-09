import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'battle/battle_overlay.dart';
import 'state/game_models.dart';
import 'state/run_notifier.dart';
import 'state/world_provider.dart';

/// `/world/review/:topicId`. 도감에서 잡은 몬스터의 주제로 복습 배틀을 한다.
/// 지도 없이 배틀 화면만 띄우고, 문제를 다 풀면 점수를 보여 주고 도감으로 돌아간다.
class ReviewPage extends ConsumerStatefulWidget {
  const ReviewPage({super.key, required this.topicId});

  final int topicId;

  @override
  ConsumerState<ReviewPage> createState() => _ReviewPageState();
}

class _ReviewPageState extends ConsumerState<ReviewPage> {
  bool _leaving = false;

  RunNotifier get _run => ref.read(reviewRunProvider(widget.topicId).notifier);

  @override
  void initState() {
    super.initState();
    ref.listenManual<RunState>(
        reviewRunProvider(widget.topicId), _onRunChanged);
    // initState 안에서는 provider를 바꿀 수 없어 첫 프레임 뒤에 시작한다.
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      await _run.start();
      if (mounted &&
          ref.read(reviewRunProvider(widget.topicId)).phase ==
              RunPhase.exploring) {
        await _run.encounter();
      }
    });
  }

  void _onRunChanged(RunState? prev, RunState next) {
    final notice = next.notice;
    // 판을 못 열었을 때의 안내는 화면 본문에 남긴다.
    if (notice != null &&
        notice != prev?.notice &&
        next.phase != RunPhase.error) {
      ScaffoldMessenger.maybeOf(context)
          ?.showSnackBar(SnackBar(content: Text(notice)));
      _run.clearNotice();
    }
    if (prev?.phase != RunPhase.cleared && next.phase == RunPhase.cleared) {
      _onCleared(next.result);
    }
  }

  Future<void> _onCleared(AnswerResult? r) async {
    final score = r == null ? '' : '${r.reviewCorrect}/${r.reviewTotal} 정답';
    final defeated = r?.monsterDefeated == true;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: const Text('복습 완료!'),
        content: Text(defeated ? '$score\n몬스터를 다시 쓰러뜨렸다!' : score),
        actions: [
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('도감으로'),
          ),
        ],
      ),
    );
    _leave();
  }

  void _leave() {
    if (_leaving || !mounted) return;
    _leaving = true;
    ref.invalidate(collectionProvider);
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/world/collection');
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(reviewRunProvider(widget.topicId));
    final entry = ref
        .watch(collectionProvider)
        .valueOrNull
        ?.where((e) => e.topicId == widget.topicId)
        .firstOrNull;
    final look = ref.watch(studentLookProvider) ?? StudentLook.boy;
    final enc = s.encounter;
    final answered = s.reviewAnswered;
    final number = (s.result == null ? answered + 1 : answered)
        .clamp(1, s.reviewTotal < 1 ? 1 : s.reviewTotal);

    final Widget body = switch (s.phase) {
      RunPhase.error ||
      RunPhase.closed =>
        _Message(text: s.notice ?? '복습을 시작하지 못했다.', onBack: _leave),
      RunPhase.expired ||
      RunPhase.ended =>
        _Message(text: '복습 판이 사라졌다. 도감에서 다시 시작해 줘.', onBack: _leave),
      RunPhase.exploring when enc == null => _Message(
          text: '문제를 불러오지 못했다.', onBack: _leave, onRetry: _run.encounter),
      _ when enc != null => BattleOverlay(
          dungeonId: entry?.dungeonId ?? '',
          dungeonName: entry?.dungeonName ?? '도감 복습',
          encounter: enc,
          hp: s.hp,
          maxHp: s.maxHp,
          kills: s.kills,
          killsToBoss: s.killsToBoss,
          review: true,
          reviewNumber: number,
          reviewTotal: s.reviewTotal,
          selectedIndex: s.selectedIndex,
          result: s.result,
          onChoice: _run.answer,
          onContinue: _run.finishBattle,
          look: look,
        ),
      _ => const Center(child: CircularProgressIndicator()),
    };
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: '도감으로',
          onPressed: _leave,
        ),
        title: Text(entry?.monsterName ?? '도감 복습'),
      ),
      body: body,
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.text, required this.onBack, this.onRetry});
  final String text;
  final VoidCallback onBack;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(text, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              children: [
                if (onRetry != null)
                  OutlinedButton(
                      onPressed: onRetry, child: const Text('다시 시도')),
                FilledButton(onPressed: onBack, child: const Text('도감으로')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
