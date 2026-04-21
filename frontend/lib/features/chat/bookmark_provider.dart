import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

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
  return BookmarksNotifier();
});

class BookmarksNotifier extends StateNotifier<List<BookmarkEntry>> {
  static const _key = 'chatflow.bookmarks';
  static const _storage = FlutterSecureStorage();

  BookmarksNotifier() : super([]) {
    _load();
  }

  Future<void> _load() async {
    final raw = await _storage.read(key: _key);
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
    await _storage.write(
      key: _key,
      value: jsonEncode(state.map((e) => e.toJson()).toList()),
    );
  }
}
