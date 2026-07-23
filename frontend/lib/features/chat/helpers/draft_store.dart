import 'package:shared_preferences/shared_preferences.dart';

/// 방별 입력 draft를 SharedPreferences에 보존한다.
/// 키 형식: `chatflow.draft.<roomId>`
class DraftStore {
  static const _prefix = 'chatflow.draft.';

  /// [text]를 해당 방의 draft로 저장한다. 빈 문자열이면 삭제한다.
  Future<void> save(String roomId, String text) async {
    if (text.isEmpty) return clear(roomId);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('$_prefix$roomId', text);
  }

  /// 해당 방의 draft를 불러온다. 없으면 null.
  Future<String?> load(String roomId) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('$_prefix$roomId');
  }

  /// 해당 방의 draft를 삭제한다.
  Future<void> clear(String roomId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('$_prefix$roomId');
  }
}
