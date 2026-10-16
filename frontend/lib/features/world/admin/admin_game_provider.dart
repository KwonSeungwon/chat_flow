import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import 'admin_game_api.dart';

final adminGameApiProvider = Provider<AdminGameApi>((ref) {
  return AdminGameApi(ref.watch(dioClientProvider).dio);
});

final isGameAdminProvider = FutureProvider.autoDispose<bool>((ref) {
  return isGameAdmin(ref.watch(adminGameApiProvider));
});

/// Bumped whenever a question changes status so the other tabs reload.
final reviewRevisionProvider = StateProvider<int>((ref) => 0);
