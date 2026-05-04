import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/command_palette/command_action.dart';

void main() {
  group('GoToRoomAction', () {
    test('constructs with required fields', () {
      final action = GoToRoomAction(
        roomId: 'r1',
        roomName: '테스트방',
        roomDescription: '설명입니다',
      );
      expect(action.title, '테스트방');
      expect(action.subtitle, '설명입니다');
      expect(action.icon, Icons.chat_bubble_outline);
      expect(action.roomId, 'r1');
    });

    test('subtitle defaults to "채팅방" when description is null', () {
      final action = GoToRoomAction(roomId: 'r2', roomName: '방이름');
      expect(action.subtitle, '채팅방');
    });

    test('matchScore returns positive for substring match', () {
      final action = GoToRoomAction(roomId: 'r1', roomName: '땅콩-DM');
      expect(action.matchScore('땅'), greaterThan(0));
      expect(action.matchScore('땅콩'), greaterThan(0));
    });

    test('matchScore returns 0 for no match', () {
      final action = GoToRoomAction(roomId: 'r1', roomName: '일반방');
      expect(action.matchScore('xyz'), 0);
    });

    test('matchScore prefers earlier position', () {
      final action1 = GoToRoomAction(roomId: 'r1', roomName: '땅콩방');
      final action2 = GoToRoomAction(roomId: 'r2', roomName: '큰-땅콩');
      // '땅' at index 0 vs index 2
      expect(action1.matchScore('땅'), greaterThan(action2.matchScore('땅')));
    });

    test('matchScore returns 1 for empty query', () {
      final action = GoToRoomAction(roomId: 'r1', roomName: 'ABC');
      expect(action.matchScore(''), 1);
    });
  });

  group('ViewProfileAction', () {
    test('constructs with required fields', () {
      final action = ViewProfileAction(
        userId: 'u1',
        username: '홍길동',
        profileImageUrl: 'https://example.com/img.png',
      );
      expect(action.title, '홍길동');
      expect(action.subtitle, '사용자');
      expect(action.icon, Icons.person_outline);
      expect(action.userId, 'u1');
      expect(action.profileImageUrl, 'https://example.com/img.png');
    });

    test('matchScore matches username', () {
      final action = ViewProfileAction(userId: 'u1', username: '홍길동');
      expect(action.matchScore('홍'), greaterThan(0));
      expect(action.matchScore('없는이름'), 0);
    });
  });

  group('QuickAction', () {
    test('all() returns 4 actions', () {
      final actions = QuickAction.all();
      expect(actions, hasLength(4));
    });

    test('createRoom has correct properties', () {
      final action = QuickAction.createRoom();
      expect(action.title, '새 방 만들기');
      expect(action.subtitle, '빠른 실행');
      expect(action.icon, Icons.add_circle_outline);
      expect(action.type, QuickActionType.createRoom);
    });

    test('goSearch has correct properties', () {
      final action = QuickAction.goSearch();
      expect(action.title, '전체 검색');
      expect(action.icon, Icons.search);
      expect(action.type, QuickActionType.goSearch);
    });

    test('toggleTheme has correct properties', () {
      final action = QuickAction.toggleTheme();
      expect(action.title, '다크/라이트 모드 토글');
      expect(action.icon, Icons.brightness_6_outlined);
      expect(action.type, QuickActionType.toggleTheme);
    });

    test('logout has correct properties', () {
      final action = QuickAction.logout();
      expect(action.title, '로그아웃');
      expect(action.icon, Icons.logout);
      expect(action.type, QuickActionType.logout);
    });

    test('matchScore matches title substring', () {
      final action = QuickAction.createRoom();
      expect(action.matchScore('새 방'), greaterThan(0));
      expect(action.matchScore('만들'), greaterThan(0));
      expect(action.matchScore('xyz'), 0);
    });

    test('matchScore matches subtitle for partial match', () {
      // subtitle is '빠른 실행'
      final action = QuickAction.createRoom();
      expect(action.matchScore('빠른'), greaterThan(0));
    });
  });
}
