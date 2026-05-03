enum RoomRole { owner, moderator, member }

extension RoomRoleX on RoomRole {
  String get apiValue => name.toUpperCase();

  static RoomRole fromString(String s) => RoomRole.values.firstWhere(
        (r) => r.name.toUpperCase() == s.toUpperCase(),
        orElse: () => RoomRole.member,
      );
}
