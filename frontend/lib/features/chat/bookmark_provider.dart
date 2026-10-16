import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../auth/auth_provider.dart';

/// Personal bookmark entry stored locally via SecureStorage.
class BookmarkEntry {
  final String messageId;
  final String roomId;
  final String username;
  final String content;
  final String timestamp;

  BookmarkEntry({
    required this.messageId,
    required this.roomId,
    required this.username,
    required this.content,
    required this.timestamp,
  });

  Map<String, dynamic> toJson() => {
    'messageId': messageId,
    'roomId': roomId,
    'username': username,
    'content': content,
    'timestamp': timestamp,
  };

  factory BookmarkEntry.fromJson(Map<String, dynamic> j) => BookmarkEntry(
    messageId: j['messageId'] ?? '',
    roomId: j['roomId'] ?? '',
    username: j['username'] ?? '',
    content: j['content'] ?? '',
    timestamp: j['timestamp'] ?? '',
  );
}

final bookmarksProvider =
    StateNotifierProvider<BookmarksNotifier, List<BookmarkEntry>>((ref) {
  final userId = ref.watch(authProvider.select((a) => a.userId));
  return BookmarksNotifier(userId);
});

class BookmarksNotifier extends StateNotifier<List<BookmarkEntry>> {
  static const _keyBase = 'chatflow.bookmarks';
  static const _storage = FlutterSecureStorage();

  final String? _userId;

  /// 사용자별 저장 키. userId가 없으면 읽지도 쓰지도 않는다.
  static String storageKeyFor(String userId) => '$_keyBase.$userId';

  BookmarksNotifier(this._userId) : super([]) {
    _load();
  }

  Future<void> _load() async {
    final userId = _userId;
    if (userId == null) return;
    final raw = await _storage.read(key: storageKeyFor(userId));
    if (!mounted) return;
    if (raw == null || raw.isEmpty) return;
    try {
      final list = jsonDecode(raw) as List;
      state = list
          .map((e) => BookmarkEntry.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      // Corrupted data — start fresh
    }
  }

  Future<void> add(BookmarkEntry entry) async {
    if (state.any((e) => e.messageId == entry.messageId)) return;
    state = [entry, ...state];
    await _persist();
  }

  Future<void> remove(String messageId) async {
    state = state.where((e) => e.messageId != messageId).toList();
    await _persist();
  }

  bool isBookmarked(String messageId) =>
      state.any((e) => e.messageId == messageId);

  Future<void> _persist() async {
    final userId = _userId;
    if (userId == null) return;
    await _storage.write(
      key: storageKeyFor(userId),
      value: jsonEncode(state.map((e) => e.toJson()).toList()),
    );
  }
}
