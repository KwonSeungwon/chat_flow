class UserProfile {
  final String userId;
  final String username;
  final String role;
  final String? profileImageUrl;
  final String? statusMessage;
  final String? bio;

  const UserProfile({
    required this.userId,
    required this.username,
    required this.role,
    this.profileImageUrl,
    this.statusMessage,
    this.bio,
  });

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    String? nullableString(dynamic v) {
      if (v == null) return null;
      final s = v.toString();
      return s.isEmpty ? null : s;
    }

    return UserProfile(
      userId: json['userId']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      role: json['role']?.toString() ?? 'NURSE',
      profileImageUrl: nullableString(json['profileImageUrl']),
      statusMessage: nullableString(json['statusMessage']),
      bio: nullableString(json['bio']),
    );
  }

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'username': username,
        'role': role,
        if (profileImageUrl != null) 'profileImageUrl': profileImageUrl,
        if (statusMessage != null) 'statusMessage': statusMessage,
        if (bio != null) 'bio': bio,
      };

  UserProfile copyWith({
    String? profileImageUrl,
    String? statusMessage,
    String? bio,
    bool clearProfileImageUrl = false,
    bool clearStatusMessage = false,
    bool clearBio = false,
  }) {
    return UserProfile(
      userId: userId,
      username: username,
      role: role,
      profileImageUrl: clearProfileImageUrl ? null : (profileImageUrl ?? this.profileImageUrl),
      statusMessage: clearStatusMessage ? null : (statusMessage ?? this.statusMessage),
      bio: clearBio ? null : (bio ?? this.bio),
    );
  }
}
