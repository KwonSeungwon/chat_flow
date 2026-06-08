import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_response.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/quick_reply.dart';

class QuickReplyNotifier extends StateNotifier<QuickReplySuggestions> {
  final Dio _dio;
  final String _roomId;
  String? _lastFetchedFor;

  QuickReplyNotifier(this._dio, this._roomId)
      : super(QuickReplySuggestions.empty);

  /// Refresh suggestions for the given latest message. No-op if the same
  /// messageId was already fetched (caller debounces but we de-dupe too).
  Future<void> refresh(String latestMessageId) async {
    if (latestMessageId.isEmpty) return;
    if (_lastFetchedFor == latestMessageId) return;
    _lastFetchedFor = latestMessageId;
    try {
      final resp = await _dio.post(
        '/api/ai-summary/quick-replies',
        data: {'chatRoomId': _roomId, 'latestMessageId': latestMessageId},
      );
      final inner = apiResponseMap(resp.data);
      if (inner == null) return;
      state = QuickReplySuggestions.fromJson(inner, latestMessageId);
    } catch (_) {
      // Best-effort feature; do not surface errors.
    }
  }

  void clear() {
    _lastFetchedFor = null;
    state = QuickReplySuggestions.empty;
  }
}

final quickReplyProvider = StateNotifierProvider.family<QuickReplyNotifier,
    QuickReplySuggestions, String>((ref, roomId) {
  final dio = ref.read(dioClientProvider).dio;
  return QuickReplyNotifier(dio, roomId);
});
