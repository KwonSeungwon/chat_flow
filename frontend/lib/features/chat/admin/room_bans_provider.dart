import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../shared/models/room_ban.dart';
import 'room_admin_api.dart';
import 'room_admin_api_provider.dart';

final roomBansProvider = StateNotifierProvider.family<RoomBansNotifier,
    AsyncValue<List<RoomBan>>, String>(
  (ref, roomId) {
    final api = ref.watch(roomAdminApiProvider);
    return RoomBansNotifier(api, roomId);
  },
);

class RoomBansNotifier extends StateNotifier<AsyncValue<List<RoomBan>>> {
  final RoomAdminApi _api;
  final String _roomId;

  RoomBansNotifier(this._api, this._roomId)
      : super(const AsyncValue.loading()) {
    fetch();
  }

  Future<void> fetch() async {
    try {
      final bans = await _api.listBans(_roomId);
      if (!mounted) return;
      state = AsyncValue.data(bans);
    } catch (e, st) {
      debugPrint('[RoomBansNotifier] fetch error: $e');
      if (!mounted) return;
      state = AsyncValue.error(e, st);
    }
  }
}
