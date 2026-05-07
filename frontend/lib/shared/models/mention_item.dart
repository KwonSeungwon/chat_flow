class MentionItem {
  final String messageId;
  final String chatRoomId;
  final String fromUsername;
  final String contentPreview;
  final String timestamp;
  final bool read;

  MentionItem({
    required this.messageId,
    required this.chatRoomId,
    required this.fromUsername,
    required this.contentPreview,
    required this.timestamp,
    required this.read,
  });

  factory MentionItem.fromJson(Map<String, dynamic> json) => MentionItem(
        messageId: json['messageId']?.toString() ?? '',
        chatRoomId: json['chatRoomId']?.toString() ?? '',
        fromUsername: json['fromUsername']?.toString() ?? '',
        contentPreview: json['contentPreview']?.toString() ?? '',
        timestamp: json['timestamp']?.toString() ?? '',
        read: json['read'] == true,
      );

  DateTime get when => DateTime.tryParse(timestamp) ?? DateTime.now();

  MentionItem copyWith({bool? read}) => MentionItem(
        messageId: messageId,
        chatRoomId: chatRoomId,
        fromUsername: fromUsername,
        contentPreview: contentPreview,
        timestamp: timestamp,
        read: read ?? this.read,
      );
}
