import 'dart:math';

import 'package:gossiplog/features/world/game/encounter_counter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('encounters fire every 6 to 12 steps for fixed seeds', () {
    for (final seed in [1, 7, 42, 2026]) {
      final counter = EncounterCounter(Random(seed));
      final intervals = <int>[];
      var sinceLast = 0;
      for (var i = 0; i < 600; i++) {
        sinceLast++;
        if (counter.step()) {
          intervals.add(sinceLast);
          sinceLast = 0;
        }
      }
      expect(intervals.length, greaterThan(40), reason: 'seed $seed');
      for (final n in intervals) {
        expect(n, inInclusiveRange(6, 12), reason: 'seed $seed');
      }
      // The interval is re-drawn after each encounter, so it varies.
      expect(intervals.toSet().length, greaterThan(1), reason: 'seed $seed');
    }
  });

  test('never fires during the first five steps', () {
    for (var seed = 0; seed < 50; seed++) {
      final counter = EncounterCounter(Random(seed));
      for (var i = 0; i < 5; i++) {
        expect(counter.step(), isFalse, reason: 'seed $seed step $i');
      }
    }
  });
}
