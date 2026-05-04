import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/in_room_search_provider.dart';

void main() {
  group('InRoomSearchState.copyWith', () {
    test('messageTypeFilter를 non-null로 설정', () {
      const s = InRoomSearchState();
      final next = s.copyWith(messageTypeFilter: 'CHAT');
      expect(next.messageTypeFilter, 'CHAT');
    });

    test('messageTypeFilter를 null로 명시 클리어', () {
      const s = InRoomSearchState(messageTypeFilter: 'FILE');
      final next = s.copyWith(messageTypeFilter: null);
      expect(next.messageTypeFilter, isNull);
    });

    test('messageTypeFilter 생략 시 기존 값 유지 (sentinel)', () {
      const s = InRoomSearchState(messageTypeFilter: 'AI_SUMMARY');
      final next = s.copyWith(isLoading: true);
      expect(next.messageTypeFilter, 'AI_SUMMARY');
    });

    test('clearError가 error를 null로 초기화', () {
      const s = InRoomSearchState(error: '오류');
      final next = s.copyWith(clearError: true);
      expect(next.error, isNull);
    });
  });

  group('InRoomSearchNotifier.setMessageTypeFilter', () {
    late InRoomSearchNotifier notifier;

    setUp(() {
      notifier = InRoomSearchNotifier.forTest('room1');
    });

    test('null → "FILE" 로 설정', () {
      notifier.setMessageTypeFilter('FILE');
      expect(notifier.state.messageTypeFilter, 'FILE');
    });

    test('같은 값 재선택 시 null로 토글', () {
      notifier.setMessageTypeFilter('FILE');
      notifier.setMessageTypeFilter('FILE');
      expect(notifier.state.messageTypeFilter, isNull);
    });

    test('다른 값 선택 시 교체', () {
      notifier.setMessageTypeFilter('CHAT');
      notifier.setMessageTypeFilter('AI_SUMMARY');
      expect(notifier.state.messageTypeFilter, 'AI_SUMMARY');
    });
  });
}
