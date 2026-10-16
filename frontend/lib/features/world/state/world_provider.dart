import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/auth_provider.dart';
import 'game_api.dart';
import 'game_models.dart';

/// 마을 화면 데이터(던전 목록 + 내 진행도). 계정이 바뀌면 다시 읽는다.
final worldProvider = FutureProvider.autoDispose<WorldData>((ref) async {
  ref.watch(authProvider.select((s) => s.userId));
  final api = ref.watch(gameApiProvider);
  final results = await Future.wait([api.listDungeons(), api.me()]);
  return WorldData(
    dungeons: results[0] as List<DungeonInfo>,
    me: results[1] as GameMe,
  );
});

/// 광장 분수를 누르면 여는 랭킹.
final leaderboardProvider =
    FutureProvider.autoDispose<List<LeaderboardEntry>>((ref) {
  ref.watch(authProvider.select((s) => s.userId));
  return ref.watch(gameApiProvider).leaderboard();
});
