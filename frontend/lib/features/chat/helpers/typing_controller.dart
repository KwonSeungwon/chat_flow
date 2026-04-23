import 'dart:async';

/// 타이핑 이벤트의 debounce(송신) + 사용자별 타임아웃(수신)을 관리.
///
/// 상태(`state.typingUsers`) 변이는 소비자 콜백에서 수행한다.
class TypingController {
  static const _sendDebounce = Duration(milliseconds: 500);
  static const _receiveTimeout = Duration(seconds: 3);

  Timer? _debounce;
  final Map<String, Timer> _timers = {};

  /// 송신 측: [onFire]를 500ms debounce하여 호출.
  void scheduleSend(void Function() onFire) {
    _debounce?.cancel();
    _debounce = Timer(_sendDebounce, onFire);
  }

  /// 수신 측: [username]이 타이핑 중임을 추적. 3초 내 추가 이벤트 없으면
  /// [onExpire]가 호출된다. [stop]이 true면 즉시 해제.
  void markTyping(
    String username, {
    required bool stop,
    required void Function() onAdd,
    required void Function() onRemove,
  }) {
    if (stop) {
      _timers[username]?.cancel();
      _timers.remove(username);
      onRemove();
      return;
    }
    onAdd();
    _timers[username]?.cancel();
    _timers[username] = Timer(_receiveTimeout, () {
      _timers.remove(username);
      onRemove();
    });
  }

  void dispose() {
    _debounce?.cancel();
    _debounce = null;
    for (final t in _timers.values) {
      t.cancel();
    }
    _timers.clear();
  }
}
