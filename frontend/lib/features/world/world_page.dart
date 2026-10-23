import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'admin/admin_game_provider.dart';
import 'game/village_game.dart';
import 'look_picker.dart';
import 'state/game_api.dart';
import 'state/game_models.dart';
import 'state/world_provider.dart';

/// `/world`. 마을 게임을 이 페이지 수명 동안 소유한다(주인공 그림을 바꾸면 새로
/// 만든다). 던전이나 관리자 페이지를 위에 띄우는 동안에는 엔진을 멈춘다.
///
/// 주인공 그림을 아직 안 골랐으면 선택 카드를 먼저 띄우고, 고르기 전에는 마을을
/// 만들지 않는다.
class WorldPage extends ConsumerStatefulWidget {
  const WorldPage({super.key, this.createGame});

  /// 테스트에서 게임을 갈아끼울 때만 쓴다.
  final VillageGame Function(StudentLook look)? createGame;

  @override
  ConsumerState<WorldPage> createState() => _WorldPageState();
}

class _WorldPageState extends ConsumerState<WorldPage> {
  VillageGame? _village;
  final _focus = FocusNode(debugLabel: 'village');
  bool _covered = false;

  /// 마을 메뉴에서 선택 카드를 다시 연 상태.
  bool _repicking = false;

  @override
  void initState() {
    super.initState();
    final look = ref.read(studentLookProvider) ??
        (ref.read(worldProvider).hasError ? StudentLook.boy : null);
    if (look != null) _village = _newGame(look);
    ref.listenManual<AsyncValue<WorldData>>(worldProvider, (_, next) {
      // 진행도를 못 읽어도 마을은 보인다(그림을 모르면 남학생). 다시 읽어서
      // 저장된 그림이 오면 아래 리스너가 바꾸고, 없으면 선택 카드를 띄운다.
      if (_village == null && next.hasError && !next.isLoading) {
        setState(() => _village = _newGame(StudentLook.boy));
      }
      final data = next.valueOrNull;
      final village = _village;
      if (data != null && village != null) _apply(village, data);
      if (_mustPick) village?.pauseEngine();
    });
    ref.listenManual<StudentLook?>(studentLookProvider, (_, look) {
      if (look == null || look == _village?.look) return;
      final game = _newGame(look);
      if (_covered || !(ModalRoute.of(context)?.isCurrent ?? true)) {
        game.pauseEngine();
      }
      setState(() => _village = game);
    });
  }

  /// 진행도를 읽었는데 그림이 아직 없다: 선택 카드를 띄우고 마을을 멈춘다.
  bool get _mustPick {
    final world = ref.read(worldProvider);
    return ref.read(chosenLookProvider) == null &&
        world.hasValue &&
        !world.hasError &&
        world.requireValue.me.avatar == null;
  }

  VillageGame _newGame(StudentLook look) {
    final game = (widget.createGame?.call(look) ?? VillageGame(look: look))
      ..onDoorReached = _enterDungeon
      ..onFountainTapped = _showLeaderboard;
    final data = ref.read(worldProvider).valueOrNull;
    if (data != null) _apply(game, data);
    return game;
  }

  /// 이 라우트가 맨 위가 아니면(던전 딥링크, 관리자 페이지, 시트) 엔진을 멈추고
  /// 다시 맨 위가 되면 이어 간다.
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final village = _village;
    if (village == null) return;
    final current = ModalRoute.of(context)?.isCurrent ?? true;
    if (current && village.paused && !_covered && !_repicking && !_mustPick) {
      village.resumeEngine();
    } else if (!current && !village.paused) {
      village.pauseEngine();
    }
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  void _apply(VillageGame village, WorldData data) {
    village.setDungeonState({
      for (final d in data.dungeons)
        d.id: (open: d.open, cleared: d.clearedByMe, clearCount: d.clearCount),
    });
    village.setGraduated(data.me.graduated);
  }

  void _enterDungeon(String id) => _openOver('/world/dungeon/$id');

  Future<void> _openOver(String location) async {
    if (_covered || _repicking) return;
    _covered = true;
    _village?.pauseEngine();
    await context.push(location);
    if (!mounted) return;
    _covered = false;
    _village?.resumeEngine();
    _focus.requestFocus();
    ref.invalidate(worldProvider);
  }

  void _showLeaderboard() {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (_) => const LeaderboardSheet(),
    );
  }

  void _openPicker() {
    _village?.pauseEngine();
    setState(() => _repicking = true);
  }

  void _closePicker() {
    setState(() => _repicking = false);
    if (!_covered) _village?.resumeEngine();
    _focus.requestFocus();
  }

  /// 고른 그림을 바로 쓰고 서버에 저장한다. 저장이 실패해도 이번 접속에서는
  /// 고른 그림을 그대로 쓴다.
  Future<void> _pick(StudentLook look) async {
    ref.read(chosenLookProvider.notifier).state = look;
    if (_repicking) {
      _closePicker();
    } else if (!_covered) {
      // 오류로 먼저 띄운 남학생 마을 위에서 골랐을 수 있다.
      _village?.resumeEngine();
    }
    try {
      await ref.read(gameApiProvider).setAvatar(look);
    } on GameApiException {
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          const SnackBar(content: Text('캐릭터를 저장하지 못했다. 이번 접속에서만 이 학생으로 다닌다.')));
    }
  }

  void _back() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/chat');
    }
  }

  @override
  Widget build(BuildContext context) {
    final world = ref.watch(worldProvider);
    final look = ref.watch(studentLookProvider);
    final isAdmin = ref.watch(isGameAdminProvider).valueOrNull == true;
    final village = _village;
    final picking = _repicking || _mustPick;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: '채팅으로',
          onPressed: _back,
        ),
        title: const Text('위키 던전 마을'),
        actions: [
          if (isAdmin)
            IconButton(
              icon: const Icon(Icons.admin_panel_settings_outlined),
              tooltip: '문제 검수',
              onPressed: () => _openOver('/world/admin'),
            ),
          if (village != null)
            PopupMenuButton<void>(
              tooltip: '마을 메뉴',
              itemBuilder: (_) => [
                PopupMenuItem<void>(
                  onTap: _openPicker,
                  child: const ListTile(
                    leading: Icon(Icons.face_retouching_natural_outlined),
                    title: Text('캐릭터 바꾸기'),
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
              ],
            ),
        ],
      ),
      body: Stack(
        children: [
          if (village != null)
            GameWidget<VillageGame>(
                key: ObjectKey(village), game: village, focusNode: _focus)
          else
            const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFFE6F5FF), Color(0xFFDDF2D2)],
                ),
              ),
              child: SizedBox.expand(),
            ),
          if (world.isLoading)
            const Positioned(
                top: 0, left: 0, right: 0, child: LinearProgressIndicator()),
          if (world.hasError && !world.isLoading)
            Positioned(
              top: 12,
              left: 12,
              right: 12,
              child: Material(
                elevation: 2,
                borderRadius: BorderRadius.circular(12),
                child: ListTile(
                  leading: const Icon(Icons.cloud_off_outlined),
                  title: const Text('던전 정보를 불러오지 못했다'),
                  trailing: TextButton(
                    onPressed: () => ref.invalidate(worldProvider),
                    child: const Text('다시 시도'),
                  ),
                ),
              ),
            ),
          if (picking)
            Positioned.fill(
              child: LookPicker(
                initial: look ?? StudentLook.boy,
                onPick: _pick,
                onCancel: _repicking ? _closePicker : null,
              ),
            ),
        ],
      ),
    );
  }
}

/// 광장 분수의 랭킹 게시판.
class LeaderboardSheet extends ConsumerWidget {
  const LeaderboardSheet({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final board = ref.watch(leaderboardProvider);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.7),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
              child: Text('광장 랭킹',
                  style: Theme.of(context).textTheme.titleMedium),
            ),
            Flexible(
              child: board.when(
                loading: () => const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: CircularProgressIndicator()),
                ),
                error: (_, __) => const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('랭킹을 불러오지 못했다')),
                ),
                data: (rows) => rows.isEmpty
                    ? const Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(child: Text('아직 기록이 없다')),
                      )
                    : ListView.builder(
                        shrinkWrap: true,
                        itemCount: rows.length,
                        itemBuilder: (_, i) {
                          final r = rows[i];
                          return ListTile(
                            leading: CircleAvatar(child: Text('${i + 1}')),
                            title: Text(r.username),
                            subtitle: Text('정답 ${r.correctCount}개'),
                            trailing: Text('던전 ${r.clearedCount}곳'),
                          );
                        },
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
