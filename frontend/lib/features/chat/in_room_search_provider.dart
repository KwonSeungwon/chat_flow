import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/chat_message.dart';

const _sentinel = Object();

class InRoomSearchState {
  final List<ChatMessage> results;
  final int total;
  final bool isLoading;
  final bool hasSearched;
  final String? error;
  final String? messageTypeFilter;

  const InRoomSearchState({
    this.results = const [],
    this.total = 0,
    this.isLoading = false,
    this.hasSearched = false,
    this.error,
    this.messageTypeFilter,
  });

  InRoomSearchState copyWith({
    List<ChatMessage>? results,
    int? total,
    bool? isLoading,
    bool? hasSearched,
    String? error,
    bool clearError = false,
    Object? messageTypeFilter = _sentinel,
  }) {
    return InRoomSearchState(
      results: results ?? this.results,
      total: total ?? this.total,
      isLoading: isLoading ?? this.isLoading,
      hasSearched: hasSearched ?? this.hasSearched,
      error: clearError ? null : (error ?? this.error),
      messageTypeFilter: identical(messageTypeFilter, _sentinel)
          ? this.messageTypeFilter
          : messageTypeFilter as String?,
    );
  }
}

class InRoomSearchNotifier extends StateNotifier<InRoomSearchState> {
  final DioClient? _dioClient;
  final String _roomId;

  InRoomSearchNotifier(DioClient dioClient, String roomId)
      : _dioClient = dioClient,
        _roomId = roomId,
        super(const InRoomSearchState());

  /// 테스트 전용 — HTTP 호출 없음
  InRoomSearchNotifier.forTest(String roomId)
      : _dioClient = null,
        _roomId = roomId,
        super(const InRoomSearchState());

  void setMessageTypeFilter(String? type) {
    final next = state.messageTypeFilter == type ? null : type;
    state = state.copyWith(messageTypeFilter: next);
  }

  Future<void> search({
    String? query,
    String? username,
    DateTime? startDate,
    DateTime? endDate,
  }) async {
    if (_dioClient == null) return;  // forTest instance guard
    final hasAny = (query?.trim().isNotEmpty ?? false) ||
        (username?.trim().isNotEmpty ?? false) ||
        startDate != null ||
        endDate != null ||
        state.messageTypeFilter != null;
    if (!hasAny) return;

    state = state.copyWith(isLoading: true, hasSearched: true, clearError: true);
    try {
      final params = <String, dynamic>{
        if (query != null && query.trim().isNotEmpty) 'query': query.trim(),
        if (username != null && username.trim().isNotEmpty)
          'username': username.trim(),
        if (startDate != null) 'startDate': startDate.toUtc().toIso8601String(),
        if (endDate != null) 'endDate': endDate.toUtc().toIso8601String(),
        if (state.messageTypeFilter != null)
          'messageType': state.messageTypeFilter,
        'size': 50,
      };

      final resp = await _dioClient.dio.get(
        '/api/search/rooms/$_roomId/filter',
        queryParameters: params,
      );
      final data = resp.data as Map<String, dynamic>? ?? {};
      final items = (data['content'] as List?) ?? [];
      final total = (data['totalElements'] as num?)?.toInt() ?? items.length;

      state = state.copyWith(
        results: items
            .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
            .toList(),
        total: total,
        isLoading: false,
      );
    } catch (_) {
      state = state.copyWith(
        results: [],
        total: 0,
        isLoading: false,
        error: '검색에 실패했습니다. 잠시 후 다시 시도해주세요.',
      );
    }
  }
}

final inRoomSearchProvider = StateNotifierProvider.family
    .autoDispose<InRoomSearchNotifier, InRoomSearchState, String>(
  (ref, roomId) =>
      InRoomSearchNotifier(ref.watch(dioClientProvider), roomId),
);
