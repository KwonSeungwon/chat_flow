class ChatMessage {
  final String? id;
  final String? messageId;
  final String chatRoomId;
  final String userId;
  final String username;
  final String content;
  final String timestamp;
  final String type; // CHAT, JOIN, LEAVE, SYSTEM, AI_SUMMARY
  final bool isAiGenerated;

  ChatMessage({
    this.id,
    this.messageId,
    required this.chatRoomId,
    required this.userId,
    required this.username,
    required this.content,
    required this.timestamp,
    required this.type,
    this.isAiGenerated = false,
  });

  factory ChatMessage.fromJson(Map<String, dynamic> json) {
    return ChatMessage(
      id: json['id']?.toString(),
      messageId: json['messageId']?.toString(),
      chatRoomId: json['chatRoomId']?.toString() ?? '',
      userId: json['userId']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      content: json['content']?.toString() ?? '',
      timestamp:
          json['timestamp']?.toString() ?? DateTime.now().toIso8601String(),
      type:
          json['type']?.toString() ??
          json['messageType']?.toString() ??
          'CHAT',
      isAiGenerated:
          json['isAiGenerated'] == true || json['aiGenerated'] == true,
    );
  }

  Map<String, dynamic> toJson() => {
    'chatRoomId': chatRoomId,
    'userId': userId,
    'username': username,
    'content': content,
    'timestamp': timestamp,
    'type': type,
    'isAiGenerated': isAiGenerated,
  };

  String get effectiveId => messageId ?? id ?? '$timestamp-$username';
}
