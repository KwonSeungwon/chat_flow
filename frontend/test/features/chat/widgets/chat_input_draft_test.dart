import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/chat/widgets/chat_input.dart';

/// roomId를 외부에서 바꿀 수 있는 테스트 하네스.
/// ValueNotifier로 roomId를 갱신하면 ChatInput에 didUpdateWidget이 호출된다.
class _TestHarness extends StatefulWidget {
  final ValueNotifier<String> roomIdNotifier;
  const _TestHarness({required this.roomIdNotifier});
  @override
  State<_TestHarness> createState() => _TestHarnessState();
}

class _TestHarnessState extends State<_TestHarness> {
  @override
  void initState() {
    super.initState();
    widget.roomIdNotifier.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.roomIdNotifier.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() => setState(() {});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: ChatInput(
        isConnected: true,
        onSend: (_, {String priority = 'ROUTINE'}) {},
        roomId: widget.roomIdNotifier.value,
      ),
    );
  }
}

Widget _wrap(ValueNotifier<String> roomIdNotifier) => ProviderScope(
      overrides: [
        dioClientProvider.overrideWithValue(DioClient()),
      ],
      child: MaterialApp(
        home: _TestHarness(roomIdNotifier: roomIdNotifier),
      ),
    );

void main() {
  setUpAll(() => dotenv.testLoad(fileInput: ''));

  group('ChatInput draft 방 전환', () {
    testWidgets('방 A에서 입력 후 방 B로 전환하면 컨트롤러가 비워진다', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final roomId = ValueNotifier('room-A');

      await tester.pumpWidget(_wrap(roomId));
      await tester.pump(); // draft load microtask

      // 텍스트 입력
      await tester.enterText(find.byType(TextField), 'hello from A');
      await tester.pump();

      // 방 B로 전환 (didUpdateWidget 발생)
      roomId.value = 'room-B';
      await tester.pump(); // rebuild
      await tester.pump(); // draft load microtask

      // 컨트롤러가 비어야 함 (방 B에 draft 없으므로)
      final textField = tester.widget<TextField>(find.byType(TextField));
      expect(textField.controller!.text, isEmpty);
    });

    testWidgets('방 B에 기존 draft가 있을 때 방 A에서 전환하면 방 B draft가 복원된다', (tester) async {
      SharedPreferences.setMockInitialValues({
        'chatflow.draft.room-B': 'saved draft B',
      });
      final roomId = ValueNotifier('room-A');

      await tester.pumpWidget(_wrap(roomId));
      await tester.pump();
      await tester.enterText(find.byType(TextField), 'typing in A');
      await tester.pump();

      // 방 B로 전환
      roomId.value = 'room-B';
      await tester.pump(); // rebuild
      await tester.pump(); // draft load microtask

      // 방 B의 저장된 draft가 복원돼야 함
      final textField = tester.widget<TextField>(find.byType(TextField));
      expect(textField.controller!.text, 'saved draft B');
    });

    testWidgets('방 A 입력분이 방 B로 오염되지 않고, 방 A 복귀 시 복원된다', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final roomId = ValueNotifier('room-A');

      await tester.pumpWidget(_wrap(roomId));
      await tester.pump();

      // 방 A에 텍스트 입력
      await tester.enterText(find.byType(TextField), 'A only text');
      await tester.pump();

      // 방 B로 전환
      roomId.value = 'room-B';
      await tester.pump();
      await tester.pump();

      // 방 B 입력창에 방 A 텍스트가 남아있으면 안 됨
      final textFieldB = tester.widget<TextField>(find.byType(TextField));
      expect(textFieldB.controller!.text, isNot('A only text'));
      expect(textFieldB.controller!.text, isEmpty);

      // 방 A로 복귀 — flush된 draft가 복원돼야 함
      roomId.value = 'room-A';
      await tester.pump();
      await tester.pump();
      final textFieldA = tester.widget<TextField>(find.byType(TextField));
      expect(textFieldA.controller!.text, 'A only text');
    });
  });
}
