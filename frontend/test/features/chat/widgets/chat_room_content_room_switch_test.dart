import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/chat/chat_provider.dart';
import 'package:chatflow/features/chat/widgets/chat_room_content.dart';

/// Every request answers 404 — the room screen must render without a backend.
class _NotFoundAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async =>
      ResponseBody.fromString('{}', 404, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      });

  @override
  void close({bool force = false}) {}
}

void main() {
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('switching roomId re-runs per-room init (activeRoomId follows)',
      (tester) async {
    final roomId = ValueNotifier('room-A');
    final client = DioClient()..dio.httpClientAdapter = _NotFoundAdapter();

    await tester.pumpWidget(ProviderScope(
      overrides: [dioClientProvider.overrideWithValue(client)],
      child: MaterialApp(
        home: Scaffold(
          body: ValueListenableBuilder<String>(
            valueListenable: roomId,
            builder: (_, id, __) => id.isEmpty
                ? const SizedBox()
                : ChatRoomContent(roomId: id, username: 'alice'),
          ),
        ),
      ),
    ));
    await tester.pump();

    final container =
        ProviderScope.containerOf(tester.element(find.byType(ChatRoomContent)));
    expect(container.read(activeRoomIdProvider), 'room-A');

    roomId.value = 'room-B';
    await tester.pump();
    await tester.pump();

    expect(container.read(activeRoomIdProvider), 'room-B');

    // Leave the room (scope stays up) and let deferred work settle.
    roomId.value = '';
    await tester.pump(const Duration(seconds: 30));
    expect(container.read(activeRoomIdProvider), isNull);
  });
}
