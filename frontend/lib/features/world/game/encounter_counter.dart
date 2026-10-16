import 'dart:math';

/// 던전에서 걸음을 세다가 6~12걸음 사이 무작위 간격마다 인카운터를 알린다.
class EncounterCounter {
  EncounterCounter(this._rng) {
    _draw();
  }

  static const minSteps = 6;
  static const maxSteps = 12;

  final Random _rng;
  late int _remaining;

  /// 한 걸음 걸었다. 인카운터면 true를 돌려주고 다음 간격을 새로 뽑는다.
  bool step() {
    _remaining--;
    if (_remaining > 0) return false;
    _draw();
    return true;
  }

  void _draw() => _remaining = minSteps + _rng.nextInt(maxSteps - minSteps + 1);
}
