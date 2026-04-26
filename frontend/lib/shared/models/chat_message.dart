import 'dart:convert';

enum MessageDeliveryStatus { sending, sent, failed }

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
  final bool pinned;
  /// JSON map: {"emoji": ["userId1","userId2"]}
  final Map<String, List<String>> reactions;
  final String? forwardedFrom;
  final String? localId;
  final MessageDeliveryStatus deliveryStatus;

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
    this.pinned = false,
    this.reactions = const {},
    this.forwardedFrom,
    this.localId,
    this.deliveryStatus = MessageDeliveryStatus.sent,
  });

  /// Server uses LocalDateTime (no timezone). K3s runs in UTC, so treat
  /// timezone-less timestamps as UTC so .toLocal() converts to KST correctly.
  static String _normalizeTimestamp(String? raw) {
    if (raw == null || raw.isEmpty) return DateTime.now().toUtc().toIso8601String();
    // Jackson array format: [2026, 4, 13, 12, 30, 45, 123456789]
    if (raw.startsWith('[')) {
      try {
        final parts = raw.replaceAll(RegExp(r'[\[\]\s]'), '').split(',').map(int.parse).toList();
        if (parts.length >= 6) {
          final ms = parts.length >= 7 ? parts[6] ~/ 1000000 : 0;
          return DateTime.utc(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], ms).toIso8601String();
        }
      } catch (_) {}
      return DateTime.now().toUtc().toIso8601String();
    }
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
      pinned: json['pinned'] == true,
      reactions: parseReactions(json['reactions']),
      forwardedFrom: json['forwardedFrom']?.toString(),
      deliveryStatus: MessageDeliveryStatus.sent,
    );
  }

  static Map<String, List<String>> parseReactions(dynamic raw) {
    if (raw == null) return const {};
    try {
      Map<String, dynamic> map;
      if (raw is String) {
        map = (jsonDecode(raw) as Map).cast<String, dynamic>();
      } else if (raw is Map) {
        map = raw.cast<String, dynamic>();
      } else {
        return const {};
      }
      return map.map((k, v) => MapEntry(k, (v as List).map((e) => e.toString()).toList()));
    } catch (_) {
      return const {};
    }
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
    if (forwardedFrom != null) 'forwardedFrom': forwardedFrom,
  };

  ChatMessage copyWith({
    String? id,
    String? messageId,
    String? chatRoomId,
    String? userId,
    String? username,
    String? content,
    String? timestamp,
    String? type,
    String? priority,
    bool? isAiGenerated,
    String? fileUrl,
    String? fileName,
    String? fileContentType,
    String? parentMessageId,
    String? parentMessagePreview,
    bool? deleted,
    bool? edited,
    String? editedAt,
    bool? pinned,
    Map<String, List<String>>? reactions,
    String? forwardedFrom,
    String? localId,
    MessageDeliveryStatus? deliveryStatus,
  }) {
    return ChatMessage(
      id: id ?? this.id,
      messageId: messageId ?? this.messageId,
      chatRoomId: chatRoomId ?? this.chatRoomId,
      userId: userId ?? this.userId,
      username: username ?? this.username,
      content: content ?? this.content,
      timestamp: timestamp ?? this.timestamp,
      type: type ?? this.type,
      priority: priority ?? this.priority,
      isAiGenerated: isAiGenerated ?? this.isAiGenerated,
      fileUrl: fileUrl ?? this.fileUrl,
      fileName: fileName ?? this.fileName,
      fileContentType: fileContentType ?? this.fileContentType,
      parentMessageId: parentMessageId ?? this.parentMessageId,
      parentMessagePreview: parentMessagePreview ?? this.parentMessagePreview,
      deleted: deleted ?? this.deleted,
      edited: edited ?? this.edited,
      editedAt: editedAt ?? this.editedAt,
      pinned: pinned ?? this.pinned,
      reactions: reactions ?? this.reactions,
      forwardedFrom: forwardedFrom ?? this.forwardedFrom,
      localId: localId ?? this.localId,
      deliveryStatus: deliveryStatus ?? this.deliveryStatus,
    );
  }

  String get effectiveId => messageId ?? id ?? '$timestamp-$username-${content.hashCode}';
  bool get isReply => parentMessageId != null && parentMessageId!.isNotEmpty;
  bool get isFileMessage => type.toUpperCase() == 'FILE' && fileUrl != null;
  bool get isImageFile => fileContentType != null && fileContentType!.startsWith('image/');
  bool get isPdfFile => fileContentType == 'application/pdf' ||
      (fileName != null && fileName!.toLowerCase().endsWith('.pdf'));
}
