import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../shared/models/room_role.dart';
import '../../auth/auth_provider.dart';
import 'room_members_provider.dart';

/// Returns the current user's [RoomRole] in the given room,
/// or null if the member list is not yet loaded or the user is not a member.
final currentRoomRoleProvider =
    Provider.family<RoomRole?, String>((ref, roomId) {
  final membersAsync = ref.watch(roomMembersProvider(roomId));
  final userId = ref.watch(authProvider).userId;
  if (userId == null) return null;
  final members = membersAsync.valueOrNull;
  if (members == null) return null;
  try {
    return members.firstWhere((m) => m.userId == userId).role;
  } catch (_) {
    // User not found in member list
    return null;
  }
});
