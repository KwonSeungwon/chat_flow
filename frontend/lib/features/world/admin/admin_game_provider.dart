import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import '../state/world_provider.dart';
import 'admin_game_api.dart';

final adminGameApiProvider = Provider<AdminGameApi>((ref) {
  return AdminGameApi(ref.watch(dioClientProvider).dio);
});

/// `/api/game/me`의 `admin`. 옛 서버(필드 없음)나 실패면 false.
final isGameAdminProvider = FutureProvider.autoDispose<bool>((ref) async {
  try {
    return (await ref.watch(worldProvider.future)).me.admin;
  } catch (_) {
    return false;
  }
});

/// Bumped whenever a question changes status so the other tabs reload.
final reviewRevisionProvider = StateProvider<int>((ref) => 0);
