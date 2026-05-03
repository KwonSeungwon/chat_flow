import 'room_role.dart';

class RoomMember {
  final String userId;
  final String username;
  final RoomRole role;
  final DateTime? mutedUntil;

  RoomMember({
    required this.userId,
    required this.username,
    required this.role,
    this.mutedUntil,
  });

  bool get isMuted =>
      mutedUntil != null && mutedUntil!.isAfter(DateTime.now());

  factory RoomMember.fromJson(Map<String, dynamic> json) {
    DateTime? muted;
    final raw = json['mutedUntil'];
    if (raw != null && raw.toString().isNotEmpty) {
      muted = DateTime.tryParse(raw.toString());
    }
    return RoomMember(
      userId: json['userId']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      role: RoomRoleX.fromString(json['role']?.toString() ?? 'MEMBER'),
      mutedUntil: muted,
    );
  }

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'username': username,
        'role': role.apiValue,
        if (mutedUntil != null) 'mutedUntil': mutedUntil!.toIso8601String(),
      };

  RoomMember copyWith({
    String? userId,
    String? username,
    RoomRole? role,
    DateTime? mutedUntil,
    bool clearMutedUntil = false,
  }) {
    return RoomMember(
      userId: userId ?? this.userId,
      username: username ?? this.username,
      role: role ?? this.role,
      mutedUntil: clearMutedUntil ? null : (mutedUntil ?? this.mutedUntil),
    );
  }
}
