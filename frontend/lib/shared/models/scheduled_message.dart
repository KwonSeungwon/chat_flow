class ScheduledMessage {
  final int id;
  final String chatRoomId;
  final String content;
  final String scheduledAt; // ISO-8601 LocalDateTime string (no offset)
  final String status;      // PENDING | SENT | CANCELED | FAILED
  final String createdAt;

  ScheduledMessage({
    required this.id,
    required this.chatRoomId,
    required this.content,
    required this.scheduledAt,
    required this.status,
    required this.createdAt,
  });

  factory ScheduledMessage.fromJson(Map<String, dynamic> json) => ScheduledMessage(
        id: _parseId(json['id']),
        chatRoomId: json['chatRoomId']?.toString() ?? '',
        content: json['content']?.toString() ?? '',
        scheduledAt: json['scheduledAt']?.toString() ?? '',
        status: json['status']?.toString() ?? 'PENDING',
        createdAt: json['createdAt']?.toString() ?? '',
      );

  DateTime get scheduledAtDateTime =>
      DateTime.tryParse(scheduledAt) ?? DateTime.now();

  bool get isPending => status == 'PENDING';

  /// Parse id tolerantly: accept num, String, or null; default to 0.
  static int _parseId(dynamic value) {
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value) ?? 0;
    return 0;
  }
}
