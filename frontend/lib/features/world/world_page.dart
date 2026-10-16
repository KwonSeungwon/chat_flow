import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'admin/admin_game_provider.dart';
import 'game/village_game.dart';
import 'state/game_models.dart';
import 'state/world_provider.dart';

/// `/world`. 마을 게임 하나를 이 페이지 수명 동안 소유한다. 던전이나 관리자
/// 페이지를 위에 띄우는 동안에는 엔진을 멈춘다.
class WorldPage extends ConsumerStatefulWidget {
  const WorldPage({super.key, this.game});

  /// 테스트에서 게임을 갈아끼울 때만 쓴다.
  final VillageGame? game;

  @override
  ConsumerState<WorldPage> createState() => _WorldPageState();
}

class _WorldPageState extends ConsumerState<WorldPage> {
  late final VillageGame _village;
  final _focus = FocusNode(debugLabel: 'village');
  bool _covered = false;

  @override
  void initState() {
    super.initState();
    _village = (widget.game ?? VillageGame())
      ..onDoorReached = _enterDungeon
      ..onFountainTapped = _showLeaderboard;
    final current = ref.read(worldProvider).valueOrNull;
    if (current != null) _apply(current);
    ref.listenManual<AsyncValue<WorldData>>(worldProvider, (_, next) {
      final data = next.valueOrNull;
      if (data != null) _apply(data);
    });
  }

  /// 이 라우트가 맨 위가 아니면(던전 딥링크, 관리자 페이지, 시트) 엔진을 멈추고
  /// 다시 맨 위가 되면 이어 간다.
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final current = ModalRoute.of(context)?.isCurrent ?? true;
    if (current && _village.paused && !_covered) {
      _village.resumeEngine();
    } else if (!current && !_village.paused) {
      _village.pauseEngine();
    }
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  void _apply(WorldData data) {
    _village.setDungeonState({
      for (final d in data.dungeons)
        d.id: (open: d.open, cleared: d.clearedByMe, clearCount: d.clearCount),
    });
    _village.setGraduated(data.me.graduated);
  }

  void _enterDungeon(String id) => _openOver('/world/dungeon/$id');

  Future<void> _openOver(String location) async {
    if (_covered) return;
    _covered = true;
    _village.pauseEngine();
    await context.push(location);
    if (!mounted) return;
    _covered = false;
    _village.resumeEngine();
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
    final isAdmin = ref.watch(isGameAdminProvider).valueOrNull == true;
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
        ],
      ),
      body: Stack(
        children: [
          GameWidget<VillageGame>(game: _village, focusNode: _focus),
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
