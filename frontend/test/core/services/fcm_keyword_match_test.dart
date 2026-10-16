import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/constants/storage_keys.dart';
import 'package:chatflow/core/services/fcm_service.dart';
import 'package:chatflow/features/chat/room_keywords_provider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('keywordMatch_readsPerUserKey', () async {
    FlutterSecureStorage.setMockInitialValues({
      StorageKeys.userId: 'u1',
      RoomKeywordsNotifier.storageKeyFor('u1'): jsonEncode({
        'room-1': ['lunch'],
      }),
    });

    expect(
        await FcmService.checkKeywordMatch('room-1', 'Is LUNCH ready'), isTrue);
    expect(await FcmService.checkKeywordMatch('room-1', 'all fine'), isFalse);
  });

  test('keywordMatch_otherUsersKeywords_ignored', () async {
    FlutterSecureStorage.setMockInitialValues({
      StorageKeys.userId: 'u2',
      RoomKeywordsNotifier.storageKeyFor('u1'): jsonEncode({
        'room-1': ['lunch'],
      }),
    });

    expect(await FcmService.checkKeywordMatch('room-1', 'lunch'), isFalse);
  });

  test('keywordMatch_noUser_noFilter', () async {
    FlutterSecureStorage.setMockInitialValues({
      'chatflow.roomKeywords': jsonEncode({
        'room-1': ['lunch'],
      }),
    });

    expect(await FcmService.checkKeywordMatch('room-1', 'lunch'), isFalse);
  });
}
