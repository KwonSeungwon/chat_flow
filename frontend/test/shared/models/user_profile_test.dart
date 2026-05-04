import 'package:flutter_test/flutter_test.dart';

import 'package:chatflow/shared/models/user_profile.dart';

void main() {
  group('UserProfile', () {
    test('fromJson handles full payload', () {
      final p = UserProfile.fromJson({
        'userId': 'u1',
        'username': 'alice',
        'role': 'NURSE',
        'profileImageUrl': 'https://cdn/avatar.png',
        'statusMessage': 'in a meeting',
        'bio': 'Senior nurse',
      });
      expect(p.userId, 'u1');
      expect(p.profileImageUrl, 'https://cdn/avatar.png');
      expect(p.statusMessage, 'in a meeting');
      expect(p.bio, 'Senior nurse');
    });

    test('fromJson treats empty string and missing fields as null', () {
      final p = UserProfile.fromJson({
        'userId': 'u2',
        'username': 'bob',
        'role': 'NURSE',
        'profileImageUrl': '',
        'statusMessage': null,
      });
      expect(p.profileImageUrl, isNull);
      expect(p.statusMessage, isNull);
      expect(p.bio, isNull);
    });

    test('copyWith clearProfileImageUrl actually clears', () {
      final p = UserProfile(
        userId: 'u1',
        username: 'a',
        role: 'NURSE',
        profileImageUrl: 'https://x',
      );
      expect(p.copyWith(clearProfileImageUrl: true).profileImageUrl, isNull);
    });

    test('copyWith without clear keeps existing value', () {
      final p = UserProfile(
        userId: 'u1',
        username: 'a',
        role: 'NURSE',
        statusMessage: 'old',
      );
      expect(p.copyWith(bio: 'new').statusMessage, 'old');
    });
  });
}
