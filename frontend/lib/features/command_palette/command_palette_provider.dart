import 'dart:async';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../chat/chat_rooms_provider.dart';
import 'command_action.dart';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

class CommandPaletteState {
  final String query;
  final List<CommandAction> results;
  final bool isSearchingUsers;

  const CommandPaletteState({
    this.query = '',
    this.results = const [],
    this.isSearchingUsers = false,
  });

  CommandPaletteState copyWith({
    String? query,
    List<CommandAction>? results,
    bool? isSearchingUsers,
  }) {
    return CommandPaletteState(
      query: query ?? this.query,
      results: results ?? this.results,
      isSearchingUsers: isSearchingUsers ?? this.isSearchingUsers,
    );
  }
}

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

final commandPaletteProvider =
    StateNotifierProvider.autoDispose<CommandPaletteNotifier, CommandPaletteState>(
  (ref) => CommandPaletteNotifier(ref),
);

// ---------------------------------------------------------------------------
// Notifier
// ---------------------------------------------------------------------------

class CommandPaletteNotifier extends StateNotifier<CommandPaletteState> {
  final Ref _ref;
  Timer? _debounceTimer;

  static const _maxResults = 12;
  static const _debounceDuration = Duration(milliseconds: 200);

  CommandPaletteNotifier(this._ref) : super(const CommandPaletteState()) {
    // Initial state: show quick actions only
    state = CommandPaletteState(results: QuickAction.all());
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    super.dispose();
  }

  /// Update search query and recompute results.
  void updateQuery(String query) {
    final trimmed = query.trim();
    state = state.copyWith(query: trimmed);

    if (trimmed.isEmpty) {
      _debounceTimer?.cancel();
      state = state.copyWith(results: QuickAction.all(), isSearchingUsers: false);
      return;
    }

    // Immediately compute room + quick action matches (local, no debounce)
    final localResults = _computeLocalResults(trimmed);
    state = state.copyWith(results: localResults);

    // Debounce user search (network call)
    _debounceTimer?.cancel();
    if (trimmed.length >= 2) {
      state = state.copyWith(isSearchingUsers: true);
      _debounceTimer = Timer(_debounceDuration, () => _searchUsers(trimmed));
    }
  }

  List<CommandAction> _computeLocalResults(String query) {
    final scored = <(CommandAction, int)>[];

    // 1. Room matches
    final rooms = _ref.read(chatRoomsProvider).valueOrNull ?? [];
    for (final room in rooms) {
      final action = GoToRoomAction(
        roomId: room.id,
        roomName: room.name,
        roomDescription: room.description,
      );
      final score = action.matchScore(query);
      if (score > 0) scored.add((action, score));
    }

    // 2. Quick action matches
    for (final qa in QuickAction.all()) {
      final score = qa.matchScore(query);
      if (score > 0) scored.add((qa, score));
    }

    scored.sort((a, b) => b.$2.compareTo(a.$2));
    return scored.take(_maxResults).map((e) => e.$1).toList();
  }

  Future<void> _searchUsers(String query) async {
    if (!mounted) return;
    try {
      final dio = _ref.read(dioClientProvider).dio;
      final resp = await dio.get('/api/users/search', queryParameters: {'q': query});
      if (!mounted) return;

      final data = resp.data;
      List<dynamic> users = [];
      if (data is Map && data['data'] is List) {
        users = data['data'] as List;
      }

      final userActions = users.map((u) {
        final map = u as Map<String, dynamic>;
        return ViewProfileAction(
          userId: map['userId']?.toString() ?? '',
          username: map['username']?.toString() ?? '',
          profileImageUrl: map['profileImageUrl']?.toString(),
        );
      }).toList();

      // Merge with current local results: insert user actions after rooms, before quick actions
      final currentQuery = state.query;
      if (currentQuery != query) return; // query changed while fetching

      final localResults = _computeLocalResults(currentQuery);
      final merged = _mergeResults(localResults, userActions, currentQuery);
      state = state.copyWith(results: merged, isSearchingUsers: false);
    } catch (e) {
      debugPrint('[CommandPalette] user search error: $e');
      if (mounted) {
        state = state.copyWith(isSearchingUsers: false);
      }
    }
  }

  /// Merge local results with user search results, maintaining score order.
  List<CommandAction> _mergeResults(
    List<CommandAction> local,
    List<ViewProfileAction> users,
    String query,
  ) {
    final scored = <(CommandAction, int)>[];

    for (final action in local) {
      scored.add((action, action.matchScore(query)));
    }
    for (final user in users) {
      scored.add((user, user.matchScore(query)));
    }

    scored.sort((a, b) => b.$2.compareTo(a.$2));
    return scored.take(_maxResults).map((e) => e.$1).toList();
  }
}
