import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/auth/auth_provider.dart';

void main() {
  group('AuthState.isAuthenticated', () {
    test('token이 있으면 true를 반환한다', () {
      final state = AuthState(token: 'abc123');
      expect(state.isAuthenticated, isTrue);
    });

    test('token이 null이면 false를 반환한다', () {
      const state = AuthState();
      expect(state.isAuthenticated, isFalse);
    });
  });

  group('AuthState 기본값', () {
    test('role 기본값은 NURSE이다', () {
      const state = AuthState();
      expect(state.role, 'NURSE');
    });

    test('username 기본값은 빈 문자열이다', () {
      const state = AuthState();
      expect(state.username, '');
    });

    test('isLoading 기본값은 false이다', () {
      const state = AuthState();
      expect(state.isLoading, isFalse);
    });

    test('profileImageUrl 기본값은 null이다', () {
      const state = AuthState();
      expect(state.profileImageUrl, isNull);
    });
  });

  group('AuthState.copyWith', () {
    test('token을 업데이트하면 나머지 필드는 유지된다', () {
      final original = AuthState(
        token: 'old',
        userId: 'u1',
        username: 'alice',
        role: 'DOCTOR',
        profileImageUrl: 'https://example.com/img.png',
      );
      final updated = original.copyWith(token: 'new');
      expect(updated.token, 'new');
      expect(updated.userId, 'u1');
      expect(updated.username, 'alice');
      expect(updated.role, 'DOCTOR');
      expect(updated.profileImageUrl, 'https://example.com/img.png');
    });

    test('profileImageUrl을 업데이트할 수 있다', () {
      final original = AuthState(token: 't1', username: 'bob');
      final updated = original.copyWith(profileImageUrl: 'https://cdn/avatar.jpg');
      expect(updated.profileImageUrl, 'https://cdn/avatar.jpg');
      expect(updated.token, 't1');
      expect(updated.username, 'bob');
    });

    test('isLoading을 true로 설정할 수 있다', () {
      const state = AuthState();
      final loading = state.copyWith(isLoading: true);
      expect(loading.isLoading, isTrue);
    });

    test('error를 설정할 수 있다', () {
      const state = AuthState();
      final withError = state.copyWith(error: '로그인 실패');
      expect(withError.error, '로그인 실패');
    });

    test('error를 명시적으로 null로 초기화한다 (copyWith error: null)', () {
      final state = AuthState(token: 't').copyWith(error: '이전 에러');
      final cleared = state.copyWith(error: null);
      expect(cleared.error, isNull);
    });

    test('role을 변경할 수 있다', () {
      const state = AuthState(role: 'NURSE');
      final updated = state.copyWith(role: 'DOCTOR');
      expect(updated.role, 'DOCTOR');
    });
  });

  group('AuthState profileImageUrl 포함 생성', () {
    test('프로필 이미지 URL을 직접 생성자로 설정할 수 있다', () {
      final state = AuthState(
        token: 'tok',
        userId: '42',
        username: 'user1',
        role: 'NURSE',
        profileImageUrl: '/api/files/uuid-abc/profile.png',
      );
      expect(state.profileImageUrl, '/api/files/uuid-abc/profile.png');
      expect(state.isAuthenticated, isTrue);
    });

    test('profileImageUrl이 빈 문자열이어도 저장된다', () {
      const state = AuthState(profileImageUrl: '');
      expect(state.profileImageUrl, '');
    });
  });
}
