import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/room_member.dart';
import 'package:chatflow/shared/models/room_role.dart';

void main() {
  group('RoomRole', () {
    test('apiValue returns uppercase string', () {
      expect(RoomRole.owner.apiValue, 'OWNER');
      expect(RoomRole.moderator.apiValue, 'MODERATOR');
      expect(RoomRole.member.apiValue, 'MEMBER');
    });

    test('fromString parses case-insensitively', () {
      expect(RoomRoleX.fromString('OWNER'), RoomRole.owner);
      expect(RoomRoleX.fromString('owner'), RoomRole.owner);
      expect(RoomRoleX.fromString('Moderator'), RoomRole.moderator);
      expect(RoomRoleX.fromString('MEMBER'), RoomRole.member);
    });

    test('fromString falls back to member for unknown', () {
      expect(RoomRoleX.fromString('ADMIN'), RoomRole.member);
      expect(RoomRoleX.fromString(''), RoomRole.member);
    });
  });

  group('RoomMember.fromJson', () {
    test('parses basic fields correctly', () {
      final member = RoomMember.fromJson({
        'userId': 'u1',
        'username': 'Alice',
        'role': 'OWNER',
      });
      expect(member.userId, 'u1');
      expect(member.username, 'Alice');
      expect(member.role, RoomRole.owner);
      expect(member.mutedUntil, isNull);
    });

    test('parses mutedUntil ISO string', () {
      final member = RoomMember.fromJson({
        'userId': 'u2',
        'username': 'Bob',
        'role': 'MEMBER',
        'mutedUntil': '2099-12-31T23:59:59.000',
      });
      expect(member.mutedUntil, isNotNull);
      expect(member.mutedUntil!.year, 2099);
    });

    test('handles null mutedUntil', () {
      final member = RoomMember.fromJson({
        'userId': 'u3',
        'username': 'Carol',
        'role': 'MODERATOR',
        'mutedUntil': null,
      });
      expect(member.mutedUntil, isNull);
    });

    test('handles empty mutedUntil string', () {
      final member = RoomMember.fromJson({
        'userId': 'u4',
        'username': 'Dave',
        'role': 'MEMBER',
        'mutedUntil': '',
      });
      expect(member.mutedUntil, isNull);
    });

    test('defaults role to MEMBER for unknown value', () {
      final member = RoomMember.fromJson({
        'userId': 'u5',
        'username': 'Eve',
        'role': 'SUPERADMIN',
      });
      expect(member.role, RoomRole.member);
    });
  });

  group('RoomMember.toJson', () {
    test('round-trips correctly', () {
      final original = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.owner,
        mutedUntil: DateTime.utc(2099, 12, 31),
      );
      final json = original.toJson();
      final restored = RoomMember.fromJson(json);
      expect(restored.userId, original.userId);
      expect(restored.username, original.username);
      expect(restored.role, original.role);
      expect(restored.mutedUntil, isNotNull);
    });

    test('omits mutedUntil when null', () {
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
      );
      final json = member.toJson();
      expect(json.containsKey('mutedUntil'), isFalse);
    });
  });

  group('RoomMember.isMuted', () {
    test('returns true when mutedUntil is in the future', () {
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
        mutedUntil: DateTime.now().add(const Duration(minutes: 5)),
      );
      expect(member.isMuted, isTrue);
    });

    test('returns false when mutedUntil is in the past', () {
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
        mutedUntil: DateTime.now().subtract(const Duration(minutes: 1)),
      );
      expect(member.isMuted, isFalse);
    });

    test('returns false when mutedUntil is null', () {
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
      );
      expect(member.isMuted, isFalse);
    });

    test('returns false when mutedUntil is exactly now (boundary)', () {
      // DateTime.now() in isMuted getter will be >= the stored value
      final now = DateTime.now();
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
        mutedUntil: now,
      );
      // By the time isMuted runs, DateTime.now() >= now, so isAfter returns false
      expect(member.isMuted, isFalse);
    });
  });

  group('RoomMember.copyWith', () {
    test('copies and overrides role', () {
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
      );
      final promoted = member.copyWith(role: RoomRole.moderator);
      expect(promoted.role, RoomRole.moderator);
      expect(promoted.userId, 'u1');
    });

    test('clearMutedUntil sets null', () {
      final member = RoomMember(
        userId: 'u1',
        username: 'Alice',
        role: RoomRole.member,
        mutedUntil: DateTime.now().add(const Duration(hours: 1)),
      );
      final unmuted = member.copyWith(clearMutedUntil: true);
      expect(unmuted.mutedUntil, isNull);
    });
  });
}
