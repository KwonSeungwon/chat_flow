import '../../../core/network/dio_client.dart';
import '../../../core/services/fcm_service.dart';
import '../notification_policy_provider.dart';

/// Per-room FCM topic subscribe/unsubscribe. Best-effort — failure must
/// not interrupt join/leave lifecycle. Extracted from ChatNotifier
/// (Stage 2.5).
class FcmRoomSubscription {
  final DioClient dioClient;
  /// Closure over Riverpod's NotificationPolicy lookup — keeps the helper
  /// agnostic of Ref while preserving the policy gate ('NotificationPolicy.all'
  /// only).
  final NotificationPolicy Function(String roomId) getPolicyFor;

  FcmRoomSubscription({
    required this.dioClient,
    required this.getPolicyFor,
  });

  Future<void> subscribe(String roomId) async {
    try {
      final policy = getPolicyFor(roomId);
      if (policy != NotificationPolicy.all) return;
      final token = await FcmService.getToken();
      if (token == null) return;
      await dioClient.dio.post('/api/fcm/subscribe', data: {
        'token': token,
        'roomId': roomId,
      });
    } catch (_) {
      // Best-effort — FCM failure must not interrupt room join
    }
  }

  Future<void> unsubscribe(String roomId) async {
    try {
      final token = await FcmService.getToken();
      if (token == null) return;
      await dioClient.dio.delete('/api/fcm/subscribe', data: {
        'token': token,
        'roomId': roomId,
      });
    } catch (_) {
      // Best-effort — FCM failure must not interrupt room leave
    }
  }
}
