import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../shared/models/room_member.dart';
import 'room_admin_api.dart';
import 'room_admin_api_provider.dart';

final roomMembersProvider = StateNotifierProvider.family<
    RoomMembersNotifier, AsyncValue<List<RoomMember>>, String>(
  (ref, roomId) {
    final api = ref.watch(roomAdminApiProvider);
    return RoomMembersNotifier(api, roomId);
  },
);

class RoomMembersNotifier
    extends StateNotifier<AsyncValue<List<RoomMember>>> {
  final RoomAdminApi _api;
  final String _roomId;

  RoomMembersNotifier(this._api, this._roomId)
      : super(const AsyncValue.loading()) {
    fetch();
  }

  Future<void> fetch() async {
    try {
      final members = await _api.listMembers(_roomId);
      if (!mounted) return;
      state = AsyncValue.data(members);
    } catch (e, st) {
      debugPrint('[RoomMembersNotifier] fetch error: $e');
      if (!mounted) return;
      state = AsyncValue.error(e, st);
    }
  }

  /// Called by STOMP listener when a MEMBER_LIST_UPDATED event arrives.
  /// [rawMembers] is the `members` array from the STOMP payload.
  void applyMembersUpdate(List<dynamic> rawMembers) {
    try {
      final members = rawMembers
          .map((e) => RoomMember.fromJson(e as Map<String, dynamic>))
          .toList();
      if (!mounted) return;
      state = AsyncValue.data(members);
    } catch (e) {
      debugPrint('[RoomMembersNotifier] applyMembersUpdate error: $e');
    }
  }

  /// Optimistic local removal when a kick echo is received before the full
  /// MEMBER_LIST_UPDATED broadcast arrives.
  void applyMemberRemoved(String userId) {
    final current = state.valueOrNull;
    if (current == null) return;
    final updated = current.where((m) => m.userId != userId).toList();
    state = AsyncValue.data(updated);
  }
}
