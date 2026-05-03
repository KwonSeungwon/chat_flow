import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Fired when the current user is kicked or banned from a room.
/// UI consumes this event and clears it after handling.
class KickedEvent {
  final String roomId;
  final String reason; // "KICKED" or "BANNED"
  final String? by;

  KickedEvent({required this.roomId, required this.reason, this.by});
}

/// Fired when the current user is muted in a room.
/// UI consumes and clears after displaying.
class MutedEvent {
  final String roomId;
  final DateTime mutedUntil;
  final String? by;

  MutedEvent({required this.roomId, required this.mutedUntil, this.by});
}

/// Fire-and-clear kicked event. Set when /user/queue/kicked is received.
final kickedEventProvider = StateProvider<KickedEvent?>((ref) => null);

/// Fire-and-clear muted event per room. Set when /user/queue/muted is received.
final mutedEventProvider =
    StateProvider.family<MutedEvent?, String>((ref, roomId) => null);
