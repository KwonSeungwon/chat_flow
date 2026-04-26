import 'dart:convert';
import 'package:flutter/material.dart' show IconData, Icons;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/constants/storage_keys.dart';

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

const _policyKey = 'chatflow.roomNotificationPolicies';
const _policyStorage = FlutterSecureStorage();

final roomNotificationPolicyProvider =
    StateNotifierProvider<RoomNotificationPolicyNotifier, Map<String, NotificationPolicy>>((ref) {
  return RoomNotificationPolicyNotifier();
});

class RoomNotificationPolicyNotifier extends StateNotifier<Map<String, NotificationPolicy>> {
  RoomNotificationPolicyNotifier() : super({}) { _load(); }

  Future<void> _load() async {
    try {
      final raw = await _policyStorage.read(key: _policyKey);
      if (raw != null && raw.isNotEmpty) {
        final decoded = jsonDecode(raw) as Map<String, dynamic>;
        state = decoded.map((k, v) => MapEntry(
          k,
          NotificationPolicy.values.firstWhere((e) => e.name == v, orElse: () => NotificationPolicy.all),
        ));
      }
      // Migration: absorb legacy chatflow.mutedRooms (List<String>) as MUTED, then delete
      final legacy = await _policyStorage.read(key: StorageKeys.mutedRooms);
      if (legacy != null && legacy.isNotEmpty) {
        final migrated = Map<String, NotificationPolicy>.from(state);
        for (final id in (jsonDecode(legacy) as List)) {
          migrated[id.toString()] = NotificationPolicy.muted;
        }
        state = migrated;
        await _persist();
        await _policyStorage.delete(key: StorageKeys.mutedRooms);
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
    final serializable = state.map((k, v) => MapEntry(k, v.name));
    await _policyStorage.write(key: _policyKey, value: jsonEncode(serializable));
  }
}
