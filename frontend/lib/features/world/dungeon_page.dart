import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'battle/battle_overlay.dart';
import 'game/dungeon_game.dart';
import 'game/village_game.dart';
import 'state/game_api.dart';
import 'state/run_notifier.dart';
import 'state/world_provider.dart';

const battleOverlayKey = 'battle';

/// `/world/dungeon/:id`. 던전 게임 하나를 이 페이지 수명 동안 소유하고
/// 판 상태([runNotifierProvider])에 맞춰 배틀 오버레이를 띄우고 닫는다.
class DungeonPage extends ConsumerStatefulWidget {
  const DungeonPage({super.key, required this.dungeonId, this.game});

  final String dungeonId;

  /// 테스트에서 게임을 갈아끼울 때만 쓴다.
  final DungeonGame? game;

  @override
  ConsumerState<DungeonPage> createState() => _DungeonPageState();
}

class _DungeonPageState extends ConsumerState<DungeonPage> {
  late final DungeonGame _game;
  final _focus = FocusNode(debugLabel: 'dungeon');
  bool _leaving = false;

  /// 판을 시작하기 전의 졸업 여부. 이번 클리어로 처음 졸업했는지 가린다.
  bool? _graduatedBefore;

  RunNotifier get _run =>
      ref.read(runNotifierProvider(widget.dungeonId).notifier);

  @override
  void initState() {
    super.initState();
    _game = (widget.game ?? DungeonGame(widget.dungeonId))
      ..onEncounter = _onEncounter
      // 판이 열릴 때까지 걷지 않는다.
      ..pause();
    _graduatedBefore =
        ref.read(worldProvider).valueOrNull?.me.graduated;
    ref.listenManual<RunState>(
        runNotifierProvider(widget.dungeonId), _onRunChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _run.start();
    });
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  String get _name => VillageGame.names[widget.dungeonId] ?? widget.dungeonId;

  void _onEncounter() {
    // 게임은 이미 스스로 멈췄다. 문제를 못 받으면 _onRunChanged가 다시 걷게 한다.
    _run.encounter();
  }

  void _onRunChanged(RunState? prev, RunState next) {
    final notice = next.notice;
    if (notice != null && notice != prev?.notice) {
      ScaffoldMessenger.maybeOf(context)
          ?.showSnackBar(SnackBar(content: Text(notice)));
      _run.clearNotice();
    }
    if (next.bossUnlocked != _game.bossPhase) {
      _game.setBossPhase(next.bossUnlocked);
    }
    final justAnswered = prev?.result == null && next.result != null;
    if (justAnswered && next.result!.correct && _game.isLoaded) {
      _game.avatar.celebrate();
    }
    if (prev?.phase == next.phase) return;
    switch (next.phase) {
      case RunPhase.exploring:
        _game.overlays.remove(battleOverlayKey);
        _game.resume();
        _focus.requestFocus();
      case RunPhase.battle:
        _game.overlays.add(battleOverlayKey);
      case RunPhase.cleared:
        _game.overlays.remove(battleOverlayKey);
        _onCleared();
      case RunPhase.failed:
        _game.overlays.remove(battleOverlayKey);
        _endWith('기절했다…', '하트를 모두 잃었다. 마을로 돌아간다.');
      case RunPhase.expired:
        _game.overlays.remove(battleOverlayKey);
        _endWith('던전이 무너졌다', '시간이 너무 지나 던전이 사라졌다. 마을로 돌아간다.');
      case RunPhase.closed:
        _leave(snack: '아직 공사 중인 건물이다. 문제가 더 모이면 열린다.');
      case RunPhase.error:
        _leave();
      case RunPhase.starting:
      case RunPhase.loadingEncounter:
      case RunPhase.answering:
        break;
    }
  }

  Future<void> _onCleared() async {
    ref.invalidate(worldProvider);
    await _dialog('클리어!', '$_name의 보스를 물리치고 깃발을 꽂았다.');
    if (!mounted) return;
    if (_graduatedBefore != true) {
      try {
        final me = await ref.read(gameApiProvider).me();
        if (!mounted) return;
        if (me.graduated) {
          await _dialog('졸업!', '학사모를 받았다. 던전 ${me.total}곳을 모두 정복했다.');
        }
      } on GameApiException {
        // 졸업 여부는 마을로 돌아가면 다시 읽는다.
      }
    }
    _leave();
  }

  Future<void> _endWith(String title, String body) async {
    await _dialog(title, body);
    _leave();
  }

  Future<void> _dialog(String title, String body) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('확인'),
          ),
        ],
      ),
    );
  }

  void _leave({String? snack}) {
    if (_leaving || !mounted) return;
    _leaving = true;
    if (snack != null) {
      ScaffoldMessenger.maybeOf(context)
          ?.showSnackBar(SnackBar(content: Text(snack)));
    }
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/world');
    }
  }

  @override
  Widget build(BuildContext context) {
    final starting = ref.watch(runNotifierProvider(widget.dungeonId)
        .select((s) => s.phase == RunPhase.starting));
    return Scaffold(
      appBar: AppBar(
        title: Text(_name),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: '마을로',
          onPressed: _leave,
        ),
      ),
      body: Stack(
        children: [
          GameWidget<DungeonGame>(
            game: _game,
            focusNode: _focus,
            overlayBuilderMap: {
              battleOverlayKey: (context, game) =>
                  _BattleLayer(dungeonId: widget.dungeonId, name: _name),
            },
          ),
          if (starting) const Center(child: CircularProgressIndicator()),
        ],
      ),
    );
  }
}

class _BattleLayer extends ConsumerWidget {
  const _BattleLayer({required this.dungeonId, required this.name});
  final String dungeonId;
  final String name;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(runNotifierProvider(dungeonId));
    final enc = s.encounter;
    if (enc == null) return const SizedBox.shrink();
    final run = ref.read(runNotifierProvider(dungeonId).notifier);
    return BattleOverlay(
      dungeonId: dungeonId,
      dungeonName: name,
      encounter: enc,
      hearts: s.hearts,
      kills: s.kills,
      killsToBoss: s.killsToBoss,
      selectedIndex: s.selectedIndex,
      result: s.result,
      onChoice: run.answer,
      onContinue: run.finishBattle,
    );
  }
}
