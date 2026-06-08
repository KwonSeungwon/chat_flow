import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_response.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/mention_item.dart';

class MentionsState {
  final AsyncValue<List<MentionItem>> items;
  final int unreadCount;
  const MentionsState({required this.items, required this.unreadCount});

  MentionsState copyWith({
    AsyncValue<List<MentionItem>>? items,
    int? unreadCount,
  }) =>
      MentionsState(
        items: items ?? this.items,
        unreadCount: unreadCount ?? this.unreadCount,
      );

  static const empty =
      MentionsState(items: AsyncValue.data([]), unreadCount: 0);
}

class MentionsNotifier extends StateNotifier<MentionsState> {
  final Dio _dio;
  MentionsNotifier(this._dio) : super(MentionsState.empty) {
    refresh();
    refreshUnreadCount();
  }

  Future<void> refresh({int days = 30}) async {
    state = state.copyWith(items: const AsyncValue.loading());
    try {
      final resp =
          await _dio.get('/api/chat/mentions', queryParameters: {'days': days});
      final raw = apiResponseList(resp.data);
      final items = raw
          .map((e) => MentionItem.fromJson(e as Map<String, dynamic>))
          .toList();
      state = state.copyWith(
        items: AsyncValue.data(items),
        unreadCount: items.where((m) => !m.read).length,
      );
    } catch (e, st) {
      state = state.copyWith(items: AsyncValue.error(e, st));
    }
  }

  Future<void> refreshUnreadCount({int days = 30}) async {
    try {
      final resp = await _dio.get('/api/chat/mentions/unread-count',
          queryParameters: {'days': days});
      final inner = apiResponseMap(resp.data) ?? const {};
      final count = (inner['count'] as num?)?.toInt() ?? 0;
      state = state.copyWith(unreadCount: count);
    } catch (_) {/* best-effort */}
  }

  Future<void> markRead(String messageId) async {
    try {
      await _dio.post('/api/chat/mentions/$messageId/read');
      final current = state.items.value ?? const <MentionItem>[];
      final updated = current
          .map((m) => m.messageId == messageId ? m.copyWith(read: true) : m)
          .toList();
      state = state.copyWith(
        items: AsyncValue.data(updated),
        unreadCount: updated.where((m) => !m.read).length,
      );
    } catch (_) {/* best-effort */}
  }

  Future<void> markAllRead({int days = 30}) async {
    try {
      await _dio
          .post('/api/chat/mentions/read-all', queryParameters: {'days': days});
      await refresh(days: days);
    } catch (_) {/* best-effort */}
  }
}

final mentionsProvider =
    StateNotifierProvider<MentionsNotifier, MentionsState>((ref) {
  final dio = ref.read(dioClientProvider).dio;
  return MentionsNotifier(dio);
});
