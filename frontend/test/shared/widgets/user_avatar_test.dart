import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:chatflow/shared/widgets/user_avatar.dart';

void main() {
  Widget _wrap(Widget w) => MaterialApp(home: Scaffold(body: Center(child: w)));

  testWidgets('falls back to initial letter when imageUrl is null', (tester) async {
    await tester.pumpWidget(_wrap(const UserAvatar(fallbackName: 'Alice', imageUrl: null)));
    expect(find.text('A'), findsOneWidget);
  });

  testWidgets('falls back when imageUrl is empty', (tester) async {
    await tester.pumpWidget(_wrap(const UserAvatar(fallbackName: 'Bob', imageUrl: '')));
    expect(find.text('B'), findsOneWidget);
  });

  testWidgets('displays "?" when fallbackName is empty', (tester) async {
    await tester.pumpWidget(_wrap(const UserAvatar(fallbackName: '')));
    expect(find.text('?'), findsOneWidget);
  });

  testWidgets('wraps in InkWell when onTap is provided', (tester) async {
    bool tapped = false;
    await tester.pumpWidget(_wrap(UserAvatar(
      fallbackName: 'A',
      onTap: () => tapped = true,
    )));
    await tester.tap(find.byType(InkWell));
    expect(tapped, isTrue);
  });
}
