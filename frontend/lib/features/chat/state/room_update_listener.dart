import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/auth_provider.dart';
import '../chat_rooms_provider.dart';
import '../notification_policy_provider.dart';
import '../room_keywords_provider.dart';

/// Keeps the app-level STOMP connection (`/user/queue/room-updates`) bound to
/// the signed-in user. Lives with auth rather than with the sidebar widget, so
/// unread increments keep arriving while the sidebar/drawer is not mounted.
///
/// Not autoDispose: watch it once from the app root.
final roomUpdateListenerProvider = Provider<void>((ref) {
  final (token, userId) =
      ref.watch(authProvider.select((a) => (a.token, a.userId)));
  final service = ref.read(appStompServiceProvider);
  if (token == null || userId == null) {
    service.disconnect();
    return;
  }
  service.connect(
    userId: userId,
    token: token,
    onRoomUpdate: (
      roomId,
      type, {
      List<String> mentionedUsernames = const [],
      String content = '',
      String senderUsername = '',
    }) =>
        _handleRoomUpdate(
          ref,
          roomId,
          type,
          mentionedUsernames: mentionedUsernames,
          content: content,
          senderUsername: senderUsername,
        ),
  );
});

/// Applies one room-update event: unread increment filtered by the room's
/// notification policy, plus the keyword alert banner.
void _handleRoomUpdate(
  Ref ref,
  String roomId,
  String type, {
  List<String> mentionedUsernames = const [],
  String content = '',
  String senderUsername = '',
}) {
  if (type != 'UNREAD_INCREMENT') return;
  // Skip increment if user is currently viewing this room
  if (ref.read(activeRoomIdProvider) == roomId) return;

  final policy =
      ref.read(roomNotificationPolicyProvider.notifier).policyFor(roomId);
  final currentUsername = ref.read(authProvider).username;
  final isMentioningMe = mentionedUsernames.contains(currentUsername);
  // Keyword bypass: even MUTED/MENTIONS_ONLY rooms surface unread when
  // user-defined keywords appear in the content (frontend-only).
  final isKeywordHit =
      ref.read(roomKeywordsProvider.notifier).matches(roomId, content);

  // Policy-based filtering
  switch (policy) {
    case NotificationPolicy.muted:
      if (!isKeywordHit) return; // muted — only keyword hits surface
      break;
    case NotificationPolicy.mentionsOnly:
      if (!isMentioningMe && !isKeywordHit) return;
      break;
    case NotificationPolicy.all:
      break;
  }

  // Surface in-app keyword alert banner
  if (isKeywordHit) {
    final rooms = ref.read(chatRoomsProvider).valueOrNull ?? [];
    final room = rooms.where((r) => r.id == roomId).firstOrNull;
    final roomName = room?.name ?? roomId;
    final snippet =
        content.length > 60 ? '${content.substring(0, 60)}...' : content;
    ref.read(keywordAlertProvider.notifier).state = KeywordAlert(
      roomId: roomId,
      roomName: roomName,
      senderUsername: senderUsername,
      snippet: snippet,
    );
  }

  ref.read(roomUnreadCountsProvider.notifier).increment(roomId);
}
