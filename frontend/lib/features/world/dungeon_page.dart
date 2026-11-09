import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'battle/battle_overlay.dart';
import 'game/dungeon_game.dart';
import 'game/village_game.dart';
import 'state/game_api.dart';
import 'state/game_models.dart';
import 'state/run_notifier.dart';
import 'state/world_provider.dart';

const battleOverlayKey = 'battle';

/// `/world/dungeon/:id`. 던전 게임 하나를 이 페이지 수명 동안 소유하고
/// 판 상태([runNotifierProvider])에 맞춰 배틀 오버레이를 띄우고 닫는다.
class DungeonPage extends ConsumerStatefulWidget {
  const DungeonPage({super.key, required this.dungeonId});

  final String dungeonId;

  @override
  ConsumerState<DungeonPage> createState() => _DungeonPageState();
}

class _DungeonPageState extends ConsumerState<DungeonPage> {
  /// 주인공 그림을 알 때까지(마을 데이터가 올 때까지) 만들지 않는다. 한 번
  /// 만들면 판이 끝날 때까지 바꾸지 않는다.
  DungeonGame? _created;
  DungeonGame get _game => _created!;
  final _focus = FocusNode(debugLabel: 'dungeon');
  bool _leaving = false;

  /// 판을 시작하기 전의 졸업 여부. 이번 클리어로 처음 졸업했는지 가린다.
  bool? _graduatedBefore;

  RunNotifier get _run =>
      ref.read(runNotifierProvider(widget.dungeonId).notifier);

  @override
  void initState() {
    super.initState();
    final chosen = ref.read(studentLookProvider);
    if (chosen != null) _createGame(chosen);
    // 마을 데이터를 이 페이지 동안 붙잡아 둔다(딥링크로 들어와도 판 시작 전
    // 졸업 여부와 주인공 그림을 알 수 있게). 졸업 여부는 첫 값만 기억한다.
    // 마을 데이터를 못 읽으면 남학생으로 시작한다.
    ref.listenManual<AsyncValue<WorldData>>(worldProvider, (_, next) {
      _graduatedBefore ??= next.valueOrNull?.me.graduated;
      if (_created == null && (next.hasValue || next.hasError)) {
        _createGame(ref.read(chosenLookProvider) ??
            next.valueOrNull?.me.avatar ??
            StudentLook.boy);
        if (_inited) setState(() {});
      }
    }, fireImmediately: true);
    _inited = true;
  }

  bool _inited = false;

  /// 게임을 만들고 판을 시작한다.
  void _createGame(StudentLook look) {
    _created = DungeonGame(widget.dungeonId, look: look)
      ..onEncounter = _onEncounter
      ..onExitRequest = _requestExit
      // 판이 열릴 때까지 걷지 않는다.
      ..pause();
    ref.listenManual<RunState>(
        runNotifierProvider(widget.dungeonId), _onRunChanged);
    // initState 안에서는 다른 provider를 바꿀 수 없어 첫 프레임 뒤에 시작한다.
    if (!_inited) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _run.start();
      });
    } else {
      _run.start();
    }
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
    if (_asking && _endPhases.contains(next.phase)) {
      // 나가기 확인 창이 떠 있는데 판이 끝났다. 창부터 닫아야 뒤의 안내·pop이 페이지에 닿는다.
      Navigator.of(context, rootNavigator: true)
          .popUntil((r) => r is! PopupRoute);
    }
    switch (next.phase) {
      case RunPhase.exploring:
        _game.overlays.remove(battleOverlayKey);
        // 확인 창이 떠 있으면 닫힌 뒤(계속하기)에 다시 걷는다.
        if (!_asking) {
          _game.resume();
          _focus.requestFocus();
        }
      case RunPhase.battle:
        _game.overlays.add(battleOverlayKey);
      case RunPhase.cleared:
        _game.overlays.remove(battleOverlayKey);
        _onCleared();
      case RunPhase.failed:
        _game.overlays.remove(battleOverlayKey);
        _endWith('기절했다…', 'HP를 모두 잃었다. 마을로 돌아간다.');
      case RunPhase.ended:
        _game.overlays.remove(battleOverlayKey);
        _onEnded();
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

  /// 답한 뒤 판이 이미 없다: 진행도를 다시 읽어 클리어였는지 가린다.
  Future<void> _onEnded() async {
    _graduatedBefore ??= ref.read(worldProvider).valueOrNull?.me.graduated;
    ref.invalidate(worldProvider);
    bool cleared = false;
    try {
      final world = await ref.read(worldProvider.future);
      cleared = world.dungeons
          .any((d) => d.id == widget.dungeonId && d.clearedByMe);
    } catch (_) {
      // 진행도를 못 읽으면 중립 안내로 끝낸다.
    }
    if (!mounted) return;
    if (cleared) {
      await _onCleared();
    } else {
      await _endWith('판이 끝났다', '이번 판은 이미 끝났다. 마을로 돌아간다.');
    }
  }

  Future<void> _onCleared() async {
    _graduatedBefore ??= ref.read(worldProvider).valueOrNull?.me.graduated;
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

  bool _asking = false;

  static const _endPhases = {
    RunPhase.cleared,
    RunPhase.failed,
    RunPhase.expired,
    RunPhase.ended,
    RunPhase.closed,
    RunPhase.error,
  };

  /// 판이 아직 진행 중이면 나갈지 묻고, 아니면 바로 마을로 간다.
  /// 판(처치 수·HP)은 서버에서 시간이 지나면 사라지고, 푼 문제 기록은 남는다.
  Future<void> _requestExit() async {
    if (_leaving || _asking || !mounted) return;
    final s = ref.read(runNotifierProvider(widget.dungeonId));
    final active = s.runId != null &&
        const {
          RunPhase.exploring,
          RunPhase.loadingEncounter,
          RunPhase.battle,
          RunPhase.answering,
        }.contains(s.phase);
    if (!active) {
      _leave();
      return;
    }
    _asking = true;
    _created?.pause();
    final leave = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('던전에서 나갈까?'),
        content:
            Text('이번 판의 처치 ${s.kills}/${s.killsToBoss}와 HP ${s.hp}/${s.maxHp}는 '
                '사라진다. 지금까지 푼 문제 기록은 남는다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('계속하기'),
          ),
          FilledButton(
            key: const ValueKey('exit_confirm'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('나가기'),
          ),
        ],
      ),
    );
    _asking = false;
    if (!mounted) return;
    if (leave == true) {
      _leave();
    } else if (ref.read(runNotifierProvider(widget.dungeonId)).phase ==
        RunPhase.exploring) {
      _game.resume();
      _focus.requestFocus();
    }
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
    final starting = _created == null ||
        ref.watch(runNotifierProvider(widget.dungeonId)
            .select((s) => s.phase == RunPhase.starting));
    return PopScope(
      // 시스템 뒤로가기(Android)도 나갈지 묻는다.
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _requestExit();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(_name),
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            tooltip: '마을로',
            onPressed: _requestExit,
          ),
          actions: [
            TextButton.icon(
              key: const ValueKey('dungeon_exit'),
              onPressed: _requestExit,
              icon: const Icon(Icons.logout),
              label: const Text('나가기'),
            ),
            const SizedBox(width: 8),
          ],
        ),
        body: Stack(
          children: [
            if (_created != null)
              GameWidget<DungeonGame>(
                game: _game,
                focusNode: _focus,
                overlayBuilderMap: {
                  battleOverlayKey: (context, game) => _BattleLayer(
                      dungeonId: widget.dungeonId,
                      name: _name,
                      look: game.look),
                },
              ),
            if (starting) const Center(child: CircularProgressIndicator()),
          ],
        ),
      ),
    );
  }
}

class _BattleLayer extends ConsumerWidget {
  const _BattleLayer(
      {required this.dungeonId, required this.name, required this.look});
  final String dungeonId;
  final String name;
  final StudentLook look;

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
      hp: s.hp,
      maxHp: s.maxHp,
      kills: s.kills,
      killsToBoss: s.killsToBoss,
      selectedIndex: s.selectedIndex,
      result: s.result,
      onChoice: run.answer,
      onContinue: run.finishBattle,
      look: look,
    );
  }
}
