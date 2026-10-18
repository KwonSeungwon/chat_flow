import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../auth/auth_provider.dart';

/// 방별 키워드 알림 설정. 사용자가 음소거한 방이라도 키워드 매칭 시 알림.
/// FlutterSecureStorage(JSON)에 영속화. Frontend-only — 백엔드 인지 안 함.
class RoomKeywordsNotifier extends StateNotifier<Map<String, List<String>>> {
  static const _storageKeyBase = 'gossiplog.roomKeywords';
  static const _storage = FlutterSecureStorage();

  final String? _userId;

  /// 사용자별 저장 키. userId가 없으면 읽지도 쓰지도 않는다.
  /// fcm_service.dart의 포그라운드 키워드 매칭도 이 키를 읽는다.
  static String storageKeyFor(String userId) => '$_storageKeyBase.$userId';

  RoomKeywordsNotifier(this._userId) : super({}) {
    _load();
  }

  Future<void> _persist(Map<String, List<String>> updated) async {
    final userId = _userId;
    if (userId == null) return;
    try {
      await _storage.write(
          key: storageKeyFor(userId), value: jsonEncode(updated));
    } catch (_) {/* best-effort */}
  }

  Future<void> _load() async {
    final userId = _userId;
    if (userId == null) return;
    try {
      final raw = await _storage.read(key: storageKeyFor(userId));
      if (!mounted) return;
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
    await _persist(updated);
  }

  Future<void> removeRoom(String roomId) async {
    final updated = Map<String, List<String>>.from(state);
    if (updated.remove(roomId) == null) return;
    state = updated;
    await _persist(updated);
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
        (ref) {
  final userId = ref.watch(authProvider.select((a) => a.userId));
  return RoomKeywordsNotifier(userId);
});
