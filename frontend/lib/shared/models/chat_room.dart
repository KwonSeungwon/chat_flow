class ChatRoom {
  final String id;
  final String name;
  final String? description;
  final String? color;
  final bool isPrivate;
  final int participantCount;
  final int maxParticipants;
  final String? createdAt;

  ChatRoom({
    required this.id,
    required this.name,
    this.description,
    this.color,
    this.isPrivate = false,
    required this.participantCount,
    this.maxParticipants = 10,
    this.createdAt,
  });

  factory ChatRoom.fromJson(Map<String, dynamic> json) {
    return ChatRoom(
      id: json['id']?.toString() ?? json['externalId']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      description: json['description']?.toString(),
      color: json['color']?.toString(),
      isPrivate: json['isPrivate'] == true || json['private'] == true,
      participantCount: (json['participantCount'] as num?)?.toInt() ?? 0,
      maxParticipants: (json['maxParticipants'] as num?)?.toInt() ?? 10,
      createdAt: json['createdAt']?.toString(),
    );
  }

  bool get isFull => participantCount >= maxParticipants;
}
