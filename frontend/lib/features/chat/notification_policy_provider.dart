import 'dart:convert';
import 'package:flutter/material.dart' show IconData, Icons;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/constants/storage_keys.dart';
import '../auth/auth_provider.dart';

// ---------------------------------------------------------------------------
// Per-room notification policy (ALL / MENTIONS_ONLY / MUTED)
// ---------------------------------------------------------------------------

enum NotificationPolicy { all, mentionsOnly, muted }

extension NotificationPolicyX on NotificationPolicy {
  String get label => switch (this) {
    NotificationPolicy.all => '모든 메시지',
    NotificationPolicy.mentionsOnly => '@멘션만',
    NotificationPolicy.muted => '음소거',
  };
  String get shortLabel => switch (this) {
    NotificationPolicy.all => '전체',
    NotificationPolicy.mentionsOnly => '멘션',
    NotificationPolicy.muted => '무음',
  };
  IconData get icon => switch (this) {
    NotificationPolicy.all => Icons.notifications_outlined,
    NotificationPolicy.mentionsOnly => Icons.alternate_email,
    NotificationPolicy.muted => Icons.notifications_off_outlined,
  };
}

const _policyStorage = FlutterSecureStorage();

final roomNotificationPolicyProvider =
    StateNotifierProvider<RoomNotificationPolicyNotifier, Map<String, NotificationPolicy>>((ref) {
  final userId = ref.watch(authProvider.select((a) => a.userId));
  return RoomNotificationPolicyNotifier(userId);
});

class RoomNotificationPolicyNotifier extends StateNotifier<Map<String, NotificationPolicy>> {
  final String? _userId;

  /// 사용자별 저장 키. userId가 없으면 읽지도 쓰지도 않는다.
  static String storageKeyFor(String userId) => '${StorageKeys.roomNotificationPolicies}.$userId';

  RoomNotificationPolicyNotifier(this._userId) : super({}) { _load(); }

  Future<void> _load() async {
    final userId = _userId;
    if (userId == null) return;
    try {
      final raw = await _policyStorage.read(key: storageKeyFor(userId));
      if (!mounted) return;
      if (raw != null && raw.isNotEmpty) {
        final decoded = jsonDecode(raw) as Map<String, dynamic>;
        state = decoded.map((k, v) => MapEntry(
          k,
          NotificationPolicy.values.firstWhere((e) => e.name == v, orElse: () => NotificationPolicy.all),
        ));
      }
    } catch (_) {
      // first run or corrupted — start fresh
    }
  }

  NotificationPolicy policyFor(String roomId) => state[roomId] ?? NotificationPolicy.all;

  Future<void> setPolicy(String roomId, NotificationPolicy policy) async {
    final updated = Map<String, NotificationPolicy>.from(state);
    if (policy == NotificationPolicy.all) {
      updated.remove(roomId); // default — no need to persist
    } else {
      updated[roomId] = policy;
    }
    state = updated;
    await _persist();
  }

  Future<void> removeRoom(String roomId) async {
    if (!state.containsKey(roomId)) return;
    final updated = Map<String, NotificationPolicy>.from(state);
    updated.remove(roomId);
    state = updated;
    await _persist();
  }

  Future<void> _persist() async {
    final userId = _userId;
    if (userId == null) return;
    final serializable = state.map((k, v) => MapEntry(k, v.name));
    await _policyStorage.write(
        key: storageKeyFor(userId), value: jsonEncode(serializable));
  }
}
