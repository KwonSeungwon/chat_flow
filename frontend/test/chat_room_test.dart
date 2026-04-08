import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/chat_room.dart';

void main() {
  group('ChatRoom.fromJson', () {
    test('기본 필드를 올바르게 파싱한다', () {
      final room = ChatRoom.fromJson({
        'id': 'room-1',
        'name': '일반 채팅방',
        'participantCount': 3,
      });
      expect(room.id, 'room-1');
      expect(room.name, '일반 채팅방');
      expect(room.participantCount, 3);
    });

    test('id가 없을 때 externalId를 fallback으로 사용한다', () {
      final room = ChatRoom.fromJson({
        'externalId': 'ext-42',
        'name': '외부 룸',
        'participantCount': 1,
      });
      expect(room.id, 'ext-42');
    });

    test('isPrivate가 true로 파싱된다', () {
      final room = ChatRoom.fromJson({
        'id': 'r1',
        'name': '비밀방',
        'participantCount': 2,
        'isPrivate': true,
      });
      expect(room.isPrivate, isTrue);
    });

    test('private 키도 isPrivate로 인식한다', () {
      final room = ChatRoom.fromJson({
        'id': 'r2',
        'name': '비밀방2',
        'participantCount': 1,
        'private': true,
      });
      expect(room.isPrivate, isTrue);
    });

    test('isPrivate 기본값은 false이다', () {
      final room = ChatRoom.fromJson({
        'id': 'r3',
        'name': '일반',
        'participantCount': 0,
      });
      expect(room.isPrivate, isFalse);
    });

    test('roomType 기본값은 GENERAL이다', () {
      final room = ChatRoom.fromJson({
        'id': 'r4',
        'name': '기본 타입',
        'participantCount': 0,
      });
      expect(room.roomType, 'GENERAL');
    });

    test('maxParticipants 기본값은 10이다', () {
      final room = ChatRoom.fromJson({
        'id': 'r5',
        'name': '기본 최대',
        'participantCount': 0,
      });
      expect(room.maxParticipants, 10);
    });

    test('participantCount가 숫자 형식일 때 올바르게 파싱한다', () {
      final room = ChatRoom.fromJson({
        'id': 'r6',
        'name': 'num',
        'participantCount': 7,
        'maxParticipants': 20,
      });
      expect(room.participantCount, 7);
      expect(room.maxParticipants, 20);
    });
  });

  group('ChatRoom.isFull', () {
    test('participantCount == maxParticipants이면 true를 반환한다', () {
      final room = ChatRoom(
        id: 'r1',
        name: '가득 찬 방',
        participantCount: 10,
        maxParticipants: 10,
      );
      expect(room.isFull, isTrue);
    });

    test('participantCount > maxParticipants이면 true를 반환한다', () {
      final room = ChatRoom(
        id: 'r2',
        name: '초과 방',
        participantCount: 11,
        maxParticipants: 10,
      );
      expect(room.isFull, isTrue);
    });

    test('participantCount < maxParticipants이면 false를 반환한다', () {
      final room = ChatRoom(
        id: 'r3',
        name: '여유 방',
        participantCount: 5,
        maxParticipants: 10,
      );
      expect(room.isFull, isFalse);
    });
  });

  group('ChatRoom.isHandoff', () {
    test('roomType이 HANDOFF이면 true를 반환한다', () {
      final room = ChatRoom(
        id: 'r1',
        name: '인수인계방',
        participantCount: 2,
        roomType: 'HANDOFF',
      );
      expect(room.isHandoff, isTrue);
    });

    test('roomType이 GENERAL이면 false를 반환한다', () {
      final room = ChatRoom(
        id: 'r2',
        name: '일반방',
        participantCount: 2,
        roomType: 'GENERAL',
      );
      expect(room.isHandoff, isFalse);
    });

    test('roomType이 기본값 GENERAL이면 isHandoff는 false이다', () {
      final room = ChatRoom(
        id: 'r3',
        name: '기본방',
        participantCount: 0,
      );
      expect(room.isHandoff, isFalse);
    });
  });
}
