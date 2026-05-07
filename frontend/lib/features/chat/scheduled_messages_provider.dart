import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/scheduled_message.dart';

class ScheduledMessagesNotifier
    extends StateNotifier<AsyncValue<List<ScheduledMessage>>> {
  final Dio _dio;
  ScheduledMessagesNotifier(this._dio) : super(const AsyncValue.loading()) {
    refresh();
  }

  static List<dynamic> _unwrapList(dynamic data) {
    if (data is Map && data['data'] is List) return data['data'] as List;
    if (data is List) return data;
    return const [];
  }

  static Map<String, dynamic>? _unwrapData(dynamic data) {
    if (data is Map && data['data'] is Map) {
      return (data['data'] as Map).cast<String, dynamic>();
    }
    if (data is Map) return data.cast<String, dynamic>();
    return null;
  }

  Future<void> refresh() async {
    state = const AsyncValue.loading();
    try {
      final resp = await _dio.get('/api/chat/scheduled-messages');
      final raw = _unwrapList(resp.data);
      final items = raw
          .map((e) => ScheduledMessage.fromJson(e as Map<String, dynamic>))
          .toList();
      state = AsyncValue.data(items);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  /// Schedules a new message and prepends it to the local list.
  /// Throws on backend rejection — caller should surface a SnackBar.
  Future<ScheduledMessage> schedule({
    required String chatRoomId,
    required String content,
    required DateTime scheduledAt,
  }) async {
    final resp = await _dio.post(
      '/api/chat/scheduled-messages',
      data: {
        'chatRoomId': chatRoomId,
        'content': content,
        'scheduledAt': scheduledAt.toIso8601String(),
      },
    );
    final inner = _unwrapData(resp.data);
    if (inner == null) {
      throw Exception('Unexpected response shape from schedule');
    }
    final saved = ScheduledMessage.fromJson(inner);
    final current = state.value ?? const <ScheduledMessage>[];
    state = AsyncValue.data([saved, ...current]);
    return saved;
  }

  /// Cancels a scheduled message by ID. Removes from local list on 200.
  /// 404 from server is treated as "already gone" (idempotent UX).
  Future<void> cancel(int id) async {
    try {
      await _dio.delete('/api/chat/scheduled-messages/$id');
    } on DioException catch (e) {
      // 404 = already gone (sent/canceled/never existed). Treat as success.
      if (e.response?.statusCode != 404) rethrow;
    }
    final current = state.value ?? const <ScheduledMessage>[];
    state = AsyncValue.data(current.where((m) => m.id != id).toList());
  }
}

final scheduledMessagesProvider = StateNotifierProvider<
    ScheduledMessagesNotifier, AsyncValue<List<ScheduledMessage>>>((ref) {
  final dio = ref.read(dioClientProvider).dio;
  return ScheduledMessagesNotifier(dio);
});
