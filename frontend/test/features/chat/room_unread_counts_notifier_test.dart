import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/chat_rooms_provider.dart';

void main() {
  late RoomUnreadCountsNotifier notifier;

  setUp(() {
    notifier = RoomUnreadCountsNotifier();
  });

  test('initial state is empty map', () {
    expect(notifier.debugState, isEmpty);
  });

  test('increment adds 1 to a room', () {
    notifier.increment('room-a');
    expect(notifier.debugState['room-a'], 1);
    notifier.increment('room-a');
    expect(notifier.debugState['room-a'], 2);
  });

  test('increment for absent room starts at 1', () {
    notifier.increment('new-room');
    expect(notifier.debugState['new-room'], 1);
  });

  test('reset sets count to zero', () {
    notifier.increment('room-b');
    notifier.increment('room-b');
    expect(notifier.debugState['room-b'], 2);
    notifier.reset('room-b');
    expect(notifier.debugState['room-b'], 0);
  });

  test('setCount sets an arbitrary value', () {
    notifier.setCount('room-c', 42);
    expect(notifier.debugState['room-c'], 42);
  });

  test('replaceAll replaces entire map', () {
    notifier.increment('old-room');
    notifier.replaceAll({'room-x': 5, 'room-y': 10});
    expect(notifier.debugState.containsKey('old-room'), isFalse);
    expect(notifier.debugState['room-x'], 5);
    expect(notifier.debugState['room-y'], 10);
  });

  test('mergeAll merges into existing state', () {
    notifier.setCount('room-a', 3);
    notifier.mergeAll({'room-b': 7});
    expect(notifier.debugState['room-a'], 3);
    expect(notifier.debugState['room-b'], 7);
  });

  test('mergeAll overwrites existing keys', () {
    notifier.setCount('room-a', 3);
    notifier.mergeAll({'room-a': 99});
    expect(notifier.debugState['room-a'], 99);
  });

  test('operations preserve other room counts', () {
    notifier.setCount('room-a', 1);
    notifier.setCount('room-b', 2);
    notifier.increment('room-a');
    expect(notifier.debugState['room-a'], 2);
    expect(notifier.debugState['room-b'], 2);
    notifier.reset('room-a');
    expect(notifier.debugState['room-a'], 0);
    expect(notifier.debugState['room-b'], 2);
  });
}
