import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/core/theme/app_theme.dart';
import 'package:gossiplog/features/chat/widgets/lobby_placeholder.dart';

void main() {
  Future<void> pumpAt(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(body: LobbyPlaceholder()),
    ));
  }

  testWidgets('buttons stay within 420px on a desktop', (tester) async {
    await pumpAt(tester, const Size(1920, 1080));
    expect(tester.getSize(find.byType(FilledButton)).width, 420);
    expect(tester.getSize(find.byType(OutlinedButton)).width, 420);
    expect(tester.getCenter(find.byType(FilledButton)).dx, 960);
  });

  testWidgets('on a phone the buttons fill the padded width', (tester) async {
    await pumpAt(tester, const Size(390, 844));
    expect(tester.getSize(find.byType(FilledButton)).width, 390 - 64);
  });
}
