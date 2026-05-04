import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/in_room_search_sheet.dart';
import 'package:chatflow/features/chat/in_room_search_provider.dart';

class _StubNotifier extends InRoomSearchNotifier {
  _StubNotifier() : super.forTest('room1');
}

Widget _wrap(Widget child, {_StubNotifier? stub}) {
  final notifier = stub ?? _StubNotifier();
  return ProviderScope(
    overrides: [
      inRoomSearchProvider('room1').overrideWith((_) => notifier),
    ],
    child: MaterialApp(home: Scaffold(body: child)),
  );
}

void main() {
  testWidgets('타입 칩 3개 표시 — 일반/파일/AI 요약', (tester) async {
    await tester.pumpWidget(_wrap(
      const InRoomSearchSheet(roomId: 'room1'),
    ));
    expect(find.text('일반'), findsOneWidget);
    expect(find.text('파일'), findsOneWidget);
    expect(find.text('AI 요약'), findsOneWidget);
  });

  testWidgets('파일 칩 탭 → messageTypeFilter = FILE', (tester) async {
    final stub = _StubNotifier();
    await tester.pumpWidget(_wrap(
      const InRoomSearchSheet(roomId: 'room1'),
      stub: stub,
    ));
    await tester.tap(find.text('파일'));
    await tester.pump();
    expect(stub.state.messageTypeFilter, 'FILE');
  });

  testWidgets('같은 칩 재탭 → messageTypeFilter null (토글)', (tester) async {
    final stub = _StubNotifier();
    await tester.pumpWidget(_wrap(
      const InRoomSearchSheet(roomId: 'room1'),
      stub: stub,
    ));
    await tester.tap(find.text('일반'));
    await tester.pump();
    expect(stub.state.messageTypeFilter, 'CHAT');
    await tester.tap(find.text('일반'));
    await tester.pump();
    expect(stub.state.messageTypeFilter, isNull);
  });

  testWidgets('hasSearched=true + 결과 없으면 "검색 결과가 없습니다" 표시', (tester) async {
    final stub = _StubNotifier();
    stub.setStateForTest(stub.state.copyWith(hasSearched: true));
    await tester.pumpWidget(_wrap(
      const InRoomSearchSheet(roomId: 'room1'),
      stub: stub,
    ));
    await tester.pump();
    expect(find.text('검색 결과가 없습니다'), findsOneWidget);
    expect(find.text('검색어, 발신자, 날짜 조건을 확인해보세요'), findsOneWidget);
  });

  testWidgets('발신자 필드 Key("sender_field") 존재', (tester) async {
    await tester.pumpWidget(_wrap(
      const InRoomSearchSheet(roomId: 'room1'),
    ));
    expect(find.byKey(const Key('sender_field')), findsOneWidget);
  });

}
