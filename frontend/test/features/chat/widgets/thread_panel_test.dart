import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:gossiplog/core/network/dio_client.dart';
import 'package:gossiplog/core/network/stomp_service.dart';
import 'package:gossiplog/features/chat/chat_provider.dart';
import 'package:gossiplog/features/chat/widgets/thread_panel.dart';
import 'package:gossiplog/shared/models/chat_message.dart';

/// Answers the replies endpoint with one old reply; everything else 404.
class _RepliesAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    if (options.path.endsWith('/replies')) {
      return ResponseBody.fromString(
          jsonEncode({
            'success': true,
            'data': [
              {
                'messageId': 'r1',
                'chatRoomId': 'room-1',
                'userId': 'u2',
                'username': 'bob',
                'content': 'old reply',
                'type': 'CHAT',
                'timestamp': '2026-01-01T10:00:00',
                'parentMessageId': 'p1',
              }
            ],
          }),
          200,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType],
          });
    }
    return ResponseBody.fromString('{}', 404, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('fetched replies show in the panel but stay out of the timeline',
      (tester) async {
    final client = DioClient()..dio.httpClientAdapter = _RepliesAdapter();
    final parent = ChatMessage(
      messageId: 'p1',
      chatRoomId: 'room-1',
      userId: 'u1',
      username: 'alice',
      content: 'parent',
      timestamp: '2026-01-01T09:00:00',
      type: 'CHAT',
    );

    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioClientProvider.overrideWithValue(client),
        chatNotifierProvider.overrideWith((ref, roomId) => ChatNotifier(client,
            ref: ref,
            token: 'tok',
            username: 'alice',
            userId: 'u1',
            stompService: StompService())),
      ],
      child: MaterialApp(
        home: Scaffold(body: ThreadPanel(roomId: 'room-1', parent: parent)),
      ),
    ));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }

    expect(find.text('old reply'), findsOneWidget);
    final container =
        ProviderScope.containerOf(tester.element(find.byType(ThreadPanel)));
    expect(container.read(chatNotifierProvider('room-1')).messages, isEmpty);
  });
}
