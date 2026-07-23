import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:chatflow/features/chat/helpers/draft_store.dart';

void main() {
  late DraftStore store;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    store = DraftStore();
  });

  test('save/load 라운드트립', () async {
    await store.save('room-1', '작성 중인 메시지');
    final loaded = await store.load('room-1');
    expect(loaded, '작성 중인 메시지');
  });

  test('clear 후 load는 null', () async {
    await store.save('room-2', '임시 텍스트');
    await store.clear('room-2');
    final loaded = await store.load('room-2');
    expect(loaded, isNull);
  });

  test('빈 문자열 save는 clear와 동일', () async {
    await store.save('room-3', '원래 내용');
    await store.save('room-3', '');
    final loaded = await store.load('room-3');
    expect(loaded, isNull);
  });

  test('방별 격리 — 다른 roomId 간 간섭 없음', () async {
    await store.save('room-a', 'draft A');
    await store.save('room-b', 'draft B');

    expect(await store.load('room-a'), 'draft A');
    expect(await store.load('room-b'), 'draft B');

    await store.clear('room-a');
    expect(await store.load('room-a'), isNull);
    expect(await store.load('room-b'), 'draft B');
  });

  test('존재하지 않는 방 load는 null', () async {
    final loaded = await store.load('nonexistent');
    expect(loaded, isNull);
  });
}
