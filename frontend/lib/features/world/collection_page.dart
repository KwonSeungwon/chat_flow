import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import 'battle/battle_overlay.dart';
import 'state/game_models.dart';
import 'state/world_provider.dart';

const _ink = Color(0xFF24304D);
const _muted = Color(0xFF6B7896);
const _accent = Color(0xFF4F6EE6);

const uncaughtNotice = '아직 잡지 못한 몬스터다. 던전에서 정답을 맞혀 잡아 봐.';

/// `/world/collection`. 던전별로 주제 몬스터를 보여 준다. 잡은 몬스터를 누르면
/// 그 주제로 복습 배틀을 연다.
class CollectionPage extends ConsumerWidget {
  const CollectionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final entries = ref.watch(collectionProvider);
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: '마을로',
          onPressed: () =>
              context.canPop() ? context.pop() : context.go('/world'),
        ),
        title: const Text('도감'),
      ),
      backgroundColor: const Color(0xFFF6F8FC),
      body: entries.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, __) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off_outlined, color: _muted, size: 40),
              const SizedBox(height: 12),
              const Text('도감을 불러오지 못했다'),
              const SizedBox(height: 8),
              TextButton(
                onPressed: () => ref.invalidate(collectionProvider),
                child: const Text('다시 시도'),
              ),
            ],
          ),
        ),
        data: (rows) => rows.isEmpty
            ? const Center(
                child: Padding(
                  padding: EdgeInsets.all(32),
                  child: Text('아직 도감이 비어 있다. 던전에서 몬스터를 만나 보자.',
                      textAlign: TextAlign.center),
                ),
              )
            : _Sections(rows: rows),
      ),
    );
  }
}

class _Sections extends StatelessWidget {
  const _Sections({required this.rows});
  final List<CollectionEntry> rows;

  @override
  Widget build(BuildContext context) {
    // 서버가 던전 → 주제 순으로 준다. 순서를 지키며 던전별로 묶는다.
    final groups = <String, List<CollectionEntry>>{};
    for (final e in rows) {
      groups.putIfAbsent(e.dungeonId, () => []).add(e);
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      children: [
        for (final g in groups.values) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 16, 4, 10),
            child: Row(
              children: [
                Expanded(
                  child: Text(g.first.dungeonName,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          color: _ink)),
                ),
                Text('잡은 몬스터 ${g.where((e) => e.caught).length}/${g.length}',
                    style: const TextStyle(fontSize: 13, color: _muted)),
              ],
            ),
          ),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 180,
              mainAxisExtent: 196,
              crossAxisSpacing: 10,
              mainAxisSpacing: 10,
            ),
            itemCount: g.length,
            itemBuilder: (_, i) => _MonsterCard(entry: g[i]),
          ),
        ],
      ],
    );
  }
}

class _MonsterCard extends StatelessWidget {
  const _MonsterCard({required this.entry});
  final CollectionEntry entry;

  @override
  Widget build(BuildContext context) {
    final e = entry;
    final sprite = SvgPicture.asset(
      monsterAsset(MonsterInfo(name: '', spriteKey: e.spriteKey, isBoss: false),
          e.dungeonId),
      width: 72,
      height: 72,
      // 못 잡은 몬스터는 검은 실루엣.
      colorFilter: e.caught
          ? null
          : const ColorFilter.mode(Color(0xFF2B3045), BlendMode.srcIn),
    );
    return Card(
      key: ValueKey('collection_${e.topicId}'),
      elevation: e.caught ? 1.5 : 0,
      color: e.caught ? Colors.white : const Color(0xFFE9ECF3),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () {
          if (e.caught) {
            context.push('/world/review/${e.topicId}');
          } else {
            ScaffoldMessenger.maybeOf(context)
              ?..hideCurrentSnackBar()
              ..showSnackBar(const SnackBar(content: Text(uncaughtNotice)));
          }
        },
        child: Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 14, 10, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Center(
                      child:
                          Opacity(opacity: e.caught ? 1 : 0.55, child: sprite)),
                  const SizedBox(height: 8),
                  Text(e.caught ? (e.monsterName ?? '') : '???',
                      textAlign: TextAlign.center,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: e.caught ? _ink : _muted)),
                  if (e.caught) ...[
                    const SizedBox(height: 2),
                    Text(e.topicTitle,
                        textAlign: TextAlign.center,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 12, color: _muted)),
                    const Spacer(),
                    Text('정답 ${e.correctCount}/${e.questionCount}',
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 12, color: _accent)),
                  ],
                ],
              ),
            ),
            if (e.caught && e.dueCount > 0)
              Positioned(
                top: 8,
                right: 8,
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                        colors: [Color(0xFFFF9A8B), Color(0xFFF7708A)]),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text('복습 ${e.dueCount}',
                      style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          color: Colors.white)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
