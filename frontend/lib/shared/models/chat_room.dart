class ChatRoom {
  final String id;
  final String name;
  final String? description;
  final String? color;
  final String roomType;
  final String? allowedRoles;
  final bool isPrivate;
  final int participantCount;
  final int maxParticipants;
  final String? createdBy;
  final String? createdAt;
  final String? lastMessageAt;
  final String? pinnedMessageId;

  ChatRoom({
    required this.id,
    required this.name,
    this.description,
    this.color,
    this.roomType = 'GENERAL',
    this.allowedRoles,
    this.isPrivate = false,
    required this.participantCount,
    this.maxParticipants = 10,
    this.createdBy,
    this.createdAt,
    this.lastMessageAt,
    this.pinnedMessageId,
  });

  factory ChatRoom.fromJson(Map<String, dynamic> json) {
    return ChatRoom(
      id: json['id']?.toString() ?? json['externalId']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      description: json['description']?.toString(),
      color: json['color']?.toString(),
      roomType: json['roomType']?.toString() ?? 'GENERAL',
      allowedRoles: json['allowedRoles']?.toString(),
      isPrivate: json['isPrivate'] == true || json['private'] == true,
      participantCount: (json['participantCount'] as num?)?.toInt() ?? 0,
      maxParticipants: (json['maxParticipants'] as num?)?.toInt() ?? 10,
      createdBy: json['createdBy']?.toString(),
      createdAt: json['createdAt']?.toString(),
      lastMessageAt: json['lastMessageAt']?.toString(),
      pinnedMessageId: json['pinnedMessageId']?.toString(),
    );
  }

  ChatRoom copyWith({
    String? id,
    String? name,
    String? description,
    String? color,
    String? roomType,
    String? allowedRoles,
    bool? isPrivate,
    int? participantCount,
    int? maxParticipants,
    String? createdBy,
    String? createdAt,
    String? lastMessageAt,
    String? pinnedMessageId,
  }) {
    return ChatRoom(
      id: id ?? this.id,
      name: name ?? this.name,
      description: description ?? this.description,
      color: color ?? this.color,
      roomType: roomType ?? this.roomType,
      allowedRoles: allowedRoles ?? this.allowedRoles,
      isPrivate: isPrivate ?? this.isPrivate,
      participantCount: participantCount ?? this.participantCount,
      maxParticipants: maxParticipants ?? this.maxParticipants,
      createdBy: createdBy ?? this.createdBy,
      createdAt: createdAt ?? this.createdAt,
      lastMessageAt: lastMessageAt ?? this.lastMessageAt,
      pinnedMessageId: pinnedMessageId ?? this.pinnedMessageId,
    );
  }

  bool get isFull => participantCount >= maxParticipants;
  bool get isHandoff => roomType == 'HANDOFF';
}
