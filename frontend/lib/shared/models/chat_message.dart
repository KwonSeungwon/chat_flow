class ChatMessage {
  final String? id;
  final String? messageId;
  final String chatRoomId;
  final String userId;
  final String username;
  final String content;
  final String timestamp;
  final String type; // CHAT, JOIN, LEAVE, SYSTEM, AI_SUMMARY, FILE
  final String priority; // ROUTINE, URGENT, STAT
  final bool isAiGenerated;
  final String? fileUrl;
  final String? fileName;
  final String? fileContentType;
  final String? parentMessageId;
  final String? parentMessagePreview;
  final bool deleted;
  final bool edited;
  final String? editedAt;

  ChatMessage({
    this.id,
    this.messageId,
    required this.chatRoomId,
    required this.userId,
    required this.username,
    required this.content,
    required this.timestamp,
    required this.type,
    this.priority = 'ROUTINE',
    this.isAiGenerated = false,
    this.fileUrl,
    this.fileName,
    this.fileContentType,
    this.parentMessageId,
    this.parentMessagePreview,
    this.deleted = false,
    this.edited = false,
    this.editedAt,
  });

  /// Server uses LocalDateTime (no timezone). K3s runs in UTC, so treat
  /// timezone-less timestamps as UTC so .toLocal() converts to KST correctly.
  static String _normalizeTimestamp(String? raw) {
    if (raw == null || raw.isEmpty) return DateTime.now().toUtc().toIso8601String();
    if (raw.endsWith('Z') || RegExp(r'[+-]\d{2}(:\d{2})?$').hasMatch(raw)) {
      return raw;
    }
    return '${raw}Z';
  }

  factory ChatMessage.fromJson(Map<String, dynamic> json) {
    return ChatMessage(
      id: json['id']?.toString(),
      messageId: json['messageId']?.toString(),
      chatRoomId: json['chatRoomId']?.toString() ?? '',
      userId: json['userId']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      content: json['content']?.toString() ?? '',
      timestamp: _normalizeTimestamp(json['timestamp']?.toString()),
      type:
          json['type']?.toString() ??
          json['messageType']?.toString() ??
          'CHAT',
      priority: json['priority']?.toString() ?? 'ROUTINE',
      isAiGenerated:
          json['isAiGenerated'] == true || json['aiGenerated'] == true,
      fileUrl: json['fileUrl']?.toString(),
      fileName: json['fileName']?.toString(),
      fileContentType: json['fileContentType']?.toString(),
      parentMessageId: json['parentMessageId']?.toString(),
      parentMessagePreview: json['parentMessagePreview']?.toString(),
      deleted: json['deleted'] == true,
      edited: json['edited'] == true,
      editedAt: json['editedAt']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
    'chatRoomId': chatRoomId,
    'userId': userId,
    'username': username,
    'content': content,
    'timestamp': timestamp,
    'type': type,
    'priority': priority,
    'isAiGenerated': isAiGenerated,
    if (fileUrl != null) 'fileUrl': fileUrl,
    if (fileName != null) 'fileName': fileName,
    if (fileContentType != null) 'fileContentType': fileContentType,
    if (parentMessageId != null) 'parentMessageId': parentMessageId,
    if (parentMessagePreview != null) 'parentMessagePreview': parentMessagePreview,
  };

  String get effectiveId => messageId ?? id ?? '$timestamp-$username-${content.hashCode}';
  bool get isReply => parentMessageId != null && parentMessageId!.isNotEmpty;
  bool get isFileMessage => type.toUpperCase() == 'FILE' && fileUrl != null;
  bool get isImageFile => fileContentType != null && fileContentType!.startsWith('image/');
  bool get isPdfFile => fileContentType == 'application/pdf' ||
      (fileName != null && fileName!.toLowerCase().endsWith('.pdf'));
}
