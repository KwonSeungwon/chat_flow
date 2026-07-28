import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show debugPrint;

import '../../../core/network/api_response.dart';

/// Pure helpers for @mention autocomplete.
///
/// Candidates come from the room **member** list, not from live presence.
/// The backend resolves `@name` against `room_members` when it decides who to
/// notify, so anything else either hides valid targets or offers targets that
/// will never be notified.
class MentionCandidates {
  MentionCandidates._();

  /// Normalises a `/members` or `/participants` response into
  /// `{userId, username}` maps, dropping entries without a username and
  /// de-duplicating by userId (a user may hold several live sessions).
  ///
  /// Accepts the `ApiResponse` envelope, a bare list, or the STOMP
  /// `MEMBER_LIST_UPDATED` `members` array — all three reach the same shape
  /// through [apiResponseList].
  static List<Map<String, dynamic>> parse(Object? responseData) {
    final seen = <String>{};
    final result = <Map<String, dynamic>>[];

    for (final raw in apiResponseList(responseData)) {
      if (raw is! Map) continue;
      final username = raw['username']?.toString() ?? '';
      if (username.isEmpty) continue;

      // userId is the dedup key; fall back to username so a payload without
      // userId still yields one entry per distinct name rather than none.
      final userId = raw['userId']?.toString() ?? '';
      if (!seen.add(userId.isEmpty ? 'name:$username' : userId)) continue;

      result.add({'userId': userId, 'username': username});
    }
    return result;
  }

  /// Filters [candidates] by [query] (case-insensitive substring), listing
  /// prefix matches first so typing `@김` surfaces `김철수` above `박김동`.
  /// An empty query returns every candidate in its original order.
  ///
  /// Matching is plain substring, so it works on whole syllables but not on
  /// jamo: `ㄱ` (U+3131) does not match `김` (U+AE40), which is one composed
  /// code point. Korean IMEs emit the composed syllable, so this is what the
  /// user actually types.
  ///
  /// Capped at [limit] because the suggestion list is a 150px-tall ListView —
  /// a 200-member room would otherwise build 200 rows nobody can scroll to.
  static List<Map<String, dynamic>> filter(
    List<Map<String, dynamic>> candidates,
    String query, {
    int limit = 20,
  }) {
    final q = query.toLowerCase();
    if (q.isEmpty) return candidates.take(limit).toList();

    final prefix = <Map<String, dynamic>>[];
    final contains = <Map<String, dynamic>>[];

    for (final c in candidates) {
      final name = (c['username']?.toString() ?? '').toLowerCase();
      if (name.startsWith(q)) {
        prefix.add(c);
      } else if (name.contains(q)) {
        contains.add(c);
      }
    }
    return [...prefix, ...contains].take(limit).toList();
  }
}

/// Fetches and caches a room's @mention candidates.
///
/// Sources `/rooms/{id}/members`. It used to read `/rooms/{id}/participants`,
/// which is the Valkey *presence* set — only users connected over WebSocket
/// right now. That hid every offline member, and a Valkey restart emptied it
/// outright, so autocomplete silently returned nothing. The backend resolves
/// mentions against `room_members`, making the member list both the complete
/// and the correct source. Presence stays as a fallback for callers the member
/// endpoint rejects (403 without a member row) — worst case that is the old
/// behaviour, never worse.
/// `roomMembersProvider` fetches the same endpoint, but it starts in a loading
/// state and has no presence fallback, so autocomplete keeps its own cache
/// rather than waiting on an admin-sheet provider. The duplication is
/// deliberate.
class MentionCandidateSource {
  MentionCandidateSource(
    this._dio, {
    DateTime Function() now = DateTime.now,
    Duration ttl = const Duration(seconds: 30),
    Duration emptyTtl = const Duration(seconds: 5),
  })  : _now = now,
        _ttl = ttl,
        _emptyTtl = emptyTtl;

  final Dio _dio;
  final DateTime Function() _now;
  final Duration _ttl;
  final Duration _emptyTtl;

  /// roomId → in-flight-or-resolved candidate list. Caching the *Future*
  /// (not the resolved list) collapses the burst of lookups that typing
  /// `@seungwon` fires into a single GET.
  final Map<String, _MentionCacheEntry> _cache = {};

  /// Candidates for [roomId] whose username matches [query].
  Future<List<Map<String, dynamic>>> search(String roomId, String query) async {
    return MentionCandidates.filter(await candidates(roomId), query);
  }

  /// All candidates for [roomId], from cache when it is fresh.
  Future<List<Map<String, dynamic>>> candidates(String roomId) {
    final cached = _cache[roomId];
    final ttl = cached != null && cached.empty ? _emptyTtl : _ttl;
    if (cached != null && _now().difference(cached.fetchedAt) < ttl) {
      return cached.users;
    }

    final entry = _MentionCacheEntry(_now(), _fetch(roomId));
    _cache[roomId] = entry;
    // Nothing usable came back — both endpoints failed, or presence answered
    // 200-but-empty because Valkey was cleared. Expire in seconds so the room
    // recovers quickly, but not per-keystroke: a caller with no member row
    // takes a 403 plus a fallback GET every single time.
    entry.users.then((users) => entry.empty = users.isEmpty);
    return entry.users;
  }

  /// Adopts a `MEMBER_LIST_UPDATED` payload as the new cache entry.
  ///
  /// That broadcast carries the fresh list, so there is nothing to re-fetch.
  /// It fires on role change / kick / mute / ban; join and leave do not
  /// broadcast it, which is what the TTL is for. An unusable payload just
  /// drops the entry.
  void seed(String roomId, Object? payload) {
    final users = MentionCandidates.parse(payload);
    if (users.isEmpty) {
      _cache.remove(roomId);
      return;
    }
    _cache[roomId] = _MentionCacheEntry(_now(), Future.value(users));
  }

  /// Never throws: autocomplete degrades to "no suggestions", it does not
  /// surface an error to someone mid-sentence. [candidates] relies on this —
  /// it attaches a `.then` with no error handler.
  Future<List<Map<String, dynamic>>> _fetch(String roomId) async {
    try {
      final resp = await _dio.get('/api/chat/rooms/$roomId/members');
      return MentionCandidates.parse(resp.data);
    } catch (e) {
      debugPrint('[mentions] /members failed for $roomId, trying presence: $e');
    }
    try {
      final resp = await _dio.get('/api/chat/rooms/$roomId/participants');
      return MentionCandidates.parse(resp.data);
    } catch (e) {
      debugPrint('[mentions] no candidates for $roomId: $e');
      return const [];
    }
  }
}

/// A room's @mention candidates plus when the fetch started.
class _MentionCacheEntry {
  final DateTime fetchedAt;
  final Future<List<Map<String, dynamic>>> users;

  /// Set once [users] resolves with nothing usable, which shortens this
  /// entry's lifetime. Marking the entry rather than evicting it from the map
  /// means a slow empty fetch cannot clobber a `seed` that landed meanwhile —
  /// by then this object is an orphan and the flag goes nowhere.
  bool empty = false;

  _MentionCacheEntry(this.fetchedAt, this.users);
}
