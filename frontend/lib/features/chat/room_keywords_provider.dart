import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 방별 키워드 알림 설정. 사용자가 음소거한 방이라도 키워드 매칭 시 알림.
/// FlutterSecureStorage(JSON)에 영속화. Frontend-only — 백엔드 인지 안 함.
class RoomKeywordsNotifier extends StateNotifier<Map<String, List<String>>> {
  static const _storageKey = 'chatflow.roomKeywords';
  static const _storage = FlutterSecureStorage();

  RoomKeywordsNotifier() : super({}) {
    _load();
  }

  Future<void> _load() async {
    try {
      final raw = await _storage.read(key: _storageKey);
      if (raw != null && raw.isNotEmpty) {
        final decoded = jsonDecode(raw) as Map<String, dynamic>;
        state = decoded.map((k, v) => MapEntry(
              k,
              (v as List).map((e) => e.toString()).toList(),
            ));
      }
    } catch (_) {
      // first run or corrupted — start fresh
    }
  }

  List<String> keywordsFor(String roomId) => state[roomId] ?? const [];

  Future<void> setKeywords(String roomId, List<String> keywords) async {
    final cleaned = keywords
        .map((k) => k.trim())
        .where((k) => k.isNotEmpty)
        .toSet()
        .toList();
    final updated = Map<String, List<String>>.from(state);
    if (cleaned.isEmpty) {
      updated.remove(roomId);
    } else {
      updated[roomId] = cleaned;
    }
    state = updated;
    try {
      await _storage.write(key: _storageKey, value: jsonEncode(updated));
    } catch (_) {/* best-effort */}
  }

  Future<void> removeRoom(String roomId) async {
    final updated = Map<String, List<String>>.from(state);
    if (updated.remove(roomId) == null) return;
    state = updated;
    try {
      await _storage.write(key: _storageKey, value: jsonEncode(updated));
    } catch (_) {/* best-effort */}
  }

  /// content에 keywords 중 하나라도 포함되면 true (case-insensitive)
  bool matches(String roomId, String content) {
    final list = keywordsFor(roomId);
    if (list.isEmpty || content.isEmpty) return false;
    final lower = content.toLowerCase();
    return list.any((k) => lower.contains(k.toLowerCase()));
  }
}

final roomKeywordsProvider =
    StateNotifierProvider<RoomKeywordsNotifier, Map<String, List<String>>>(
        (ref) => RoomKeywordsNotifier());
