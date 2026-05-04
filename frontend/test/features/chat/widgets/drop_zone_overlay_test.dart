import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/widgets/drop_zone_overlay.dart';

void main() {
  group('DropZoneOverlay', () {
    testWidgets('renders overlay text when active is true', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: Stack(
              children: [
                SizedBox(width: 400, height: 200),
                DropZoneOverlay(active: true),
              ],
            ),
          ),
        ),
      );

      expect(find.text('여기에 놓아 업로드'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_upload_outlined), findsOneWidget);
    });

    testWidgets('renders SizedBox.shrink when active is false', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: Stack(
              children: [
                SizedBox(width: 400, height: 200),
                DropZoneOverlay(active: false),
              ],
            ),
          ),
        ),
      );

      expect(find.text('여기에 놓아 업로드'), findsNothing);
      expect(find.byIcon(Icons.cloud_upload_outlined), findsNothing);
      // The SizedBox.shrink should be present
      expect(find.byType(SizedBox), findsWidgets);
    });
  });
}
