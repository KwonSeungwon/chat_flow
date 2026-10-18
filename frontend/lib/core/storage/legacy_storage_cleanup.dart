import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../constants/storage_keys.dart';

/// chatflow → gossiplog 개명 전에 저장된 키를 지운다. 값은 옮기지 않는다(재로그인).
/// 옛 JWT가 기기에 남지 않게 하려는 것이다. 실패해도 앱 시작은 막지 않는다.
Future<void> removeLegacyStorageKeys() async {
  try {
    const storage = FlutterSecureStorage();
    for (final key in StorageKeys.legacyKeys) {
      await storage.delete(key: key);
    }
  } catch (_) {}
  try {
    final prefs = await SharedPreferences.getInstance();
    for (final key in prefs.getKeys().toList()) {
      if (StorageKeys.legacyPrefsPrefixes.any(key.startsWith)) {
        await prefs.remove(key);
      }
    }
  } catch (_) {}
}
