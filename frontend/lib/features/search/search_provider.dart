import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/chat_message.dart';

class SearchState {
  final List<ChatMessage> results;
  final int total;
  final bool isLoading;
  final bool hasSearched;

  const SearchState({
    this.results = const [],
    this.total = 0,
    this.isLoading = false,
    this.hasSearched = false,
  });

  SearchState copyWith({
    List<ChatMessage>? results,
    int? total,
    bool? isLoading,
    bool? hasSearched,
  }) {
    return SearchState(
      results: results ?? this.results,
      total: total ?? this.total,
      isLoading: isLoading ?? this.isLoading,
      hasSearched: hasSearched ?? this.hasSearched,
    );
  }
}

class SearchNotifier extends StateNotifier<SearchState> {
  final DioClient _dioClient;

  SearchNotifier(this._dioClient) : super(const SearchState());

  Future<void> search(String query) async {
    if (query.trim().isEmpty) return;
    state = state.copyWith(isLoading: true, hasSearched: true);
    try {
      final resp = await _dioClient.dio.get(
        '/api/search/korean',
        queryParameters: {'query': query, 'size': 20},
      );
      final data = resp.data;
      List<dynamic> items;
      int total = 0;
      if (data is Map) {
        items = (data['content'] as List?) ?? [];
        total = (data['totalElements'] as num?)?.toInt() ?? items.length;
      } else {
        items = [];
      }
      state = state.copyWith(
        results:
            items
                .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
                .toList(),
        total: total,
        isLoading: false,
      );
    } catch (_) {
      state = state.copyWith(results: [], total: 0, isLoading: false);
    }
  }

}

final searchProvider =
    StateNotifierProvider<SearchNotifier, SearchState>((ref) {
  return SearchNotifier(ref.watch(dioClientProvider));
});
