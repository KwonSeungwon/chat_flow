import '../../core/utils/server_time.dart';

class RoomBan {
  final String userId;
  final String username;
  final String bannedBy;
  final String? reason;
  final DateTime bannedAt;

  RoomBan({
    required this.userId,
    required this.username,
    required this.bannedBy,
    this.reason,
    required this.bannedAt,
  });

  factory RoomBan.fromJson(Map<String, dynamic> json) {
    return RoomBan(
      userId: json['userId']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      bannedBy: json['bannedBy']?.toString() ?? '',
      reason: json['reason']?.toString(),
      bannedAt: parseServerDateTime(json['bannedAt']) ?? DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'username': username,
        'bannedBy': bannedBy,
        if (reason != null) 'reason': reason,
        'bannedAt': bannedAt.toIso8601String(),
      };
}
