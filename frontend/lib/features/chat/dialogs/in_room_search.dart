import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../widgets/in_room_search_sheet.dart';

void showInRoomSearch(BuildContext context, WidgetRef ref, String roomId) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (ctx) => InRoomSearchSheet(
      roomId: roomId,
      onResultTap: (messageId) {
        GoRouter.of(context).go('/chat/$roomId?messageId=$messageId');
      },
    ),
  );
}
