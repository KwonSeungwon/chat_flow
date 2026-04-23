/// 연결 끊김 동안 전송 대기 메시지를 버퍼링하고,
/// 재연결 시 소비자(콜백)에게 flush를 위임하는 순수 큐.
///
/// ChatNotifier의 state 변이는 소비자 책임. 이 클래스는 큐와 metadata만 관리.
class OfflineMessageQueue {
  final List<Map<String, dynamic>> _queue = [];

  bool get isEmpty => _queue.isEmpty;
  int get length => _queue.length;

  void enqueue(Map<String, dynamic> payload) {
    _queue.add(payload);
  }

  /// 큐를 drain하고 각 메시지를 순서대로 [onSend]에 전달한다.
  /// flush 전에 큐가 가진 모든 `_localId` 값을 [onDedup]으로 넘겨 호출자가
  /// 로컬에 쌓인 sending 메시지 중복 제거를 할 수 있게 한다.
  void flush({
    required void Function(Set<String> queuedLocalIds) onDedup,
    required void Function(Map<String, dynamic> payload) onSend,
  }) {
    if (_queue.isEmpty) return;
    final queued = List<Map<String, dynamic>>.from(_queue);
    _queue.clear();
    final localIds = queued
        .map((m) => m['_localId']?.toString())
        .whereType<String>()
        .toSet();
    onDedup(localIds);
    for (final msg in queued) {
      onSend(msg);
    }
  }

  void clear() => _queue.clear();
}
