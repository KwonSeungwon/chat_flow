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

/// 이번 접속에서 고른 주인공 그림. 저장(PUT)이 실패해도 이 값은 남아 이번
/// 접속 동안 쓴다. 계정이 바뀌면 비운다.
final chosenLookProvider = StateProvider<StudentLook?>((ref) {
  ref.watch(authProvider.select((s) => s.userId));
  return null;
});

/// 주인공 그림: 이번 접속에서 고른 것, 없으면 서버에 저장된 것. 둘 다 없으면
/// null이다(마을이 선택 화면을 띄운다).
final studentLookProvider = Provider.autoDispose<StudentLook?>((ref) =>
    ref.watch(chosenLookProvider) ??
    ref.watch(worldProvider).valueOrNull?.me.avatar);
