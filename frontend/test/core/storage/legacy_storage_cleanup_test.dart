import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gossiplog/core/constants/storage_keys.dart';
import 'package:gossiplog/core/storage/legacy_storage_cleanup.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('legacyRole은 실제로 존재했던 chatflow-role 키다', () {
    expect(StorageKeys.legacyRole, 'chatflow-role');
  });

  test('chatflow 시절 키는 지우고 gossiplog 키는 그대로 둔다', () async {
    FlutterSecureStorage.setMockInitialValues({
      'chatflow-token': 'old-jwt',
      'chatflow-userId': 'u',
      'chatflow-username': 'n',
      'chatflow-role': 'DOCTOR',
      'chatflow-profileImage': '/a.png',
      StorageKeys.token: 'new-jwt',
    });
    SharedPreferences.setMockInitialValues({
      'chatflow-theme-mode': 'dark',
      'chatflow.fontScale': 1.2,
      'chatflow.bookmarks.u1': 'x',
      'chatflow.roomKeywords.u1': 'x',
      'chatflow.roomNotificationPolicies': 'x',
      'chatflow.draft.room1': 'hello',
      'gossiplog-theme-mode': 'light',
      'gossiplog.draft.room1': 'keep',
    });

    await removeLegacyStorageKeys();

    const s = FlutterSecureStorage();
    for (final k in StorageKeys.legacyKeys) {
      expect(await s.read(key: k), isNull, reason: k);
    }
    expect(await s.read(key: StorageKeys.token), 'new-jwt');
    final p = await SharedPreferences.getInstance();
    expect(p.getKeys(),
        unorderedEquals(['gossiplog-theme-mode', 'gossiplog.draft.room1']));
  });
}
