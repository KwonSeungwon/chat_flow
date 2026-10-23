/// 마을 24×24 상수 격자.
///
/// `G` 잔디, `P` 길, `W` 물, `T` 나무, `B` 건물 점유, `D` 문, `F` 분수, `S` 시작점.
/// 각 건물은 3×3을 차지하고 문은 앞(row+1) 가운데 칸이다. 분수는 [fountainTile]
/// 둘레 3×3을 덮으므로 그 9칸이 모두 `F`(걸을 수 없음)다.
class VillageMap {
  VillageMap._();

  static const int size = 24;
  static const int footprint = 3;

  static const List<String> rows = [
    'TTGGGGGGGGTGGGGGGGGGGGTT',
    'TGGGTGGGGGGGGGGGGGGTGGGT',
    'GGGGGGTGPGGGGTGPGGGGGGGG',
    'GGGBBBGGPGBBBGGPGBBBGGTG',
    'GGGBBBGGPGBBBGGPGBBBGGGG',
    'GGTBBBGGPGBBBGGPGBBBGGGG',
    'GGGGDGGGPGGDGGGPGGDGGGGG',
    'GGPPPPPPPPPPPPPPPPPPPPGT',
    'GGGGGGGGPGGGGGGPGGGGGGGG',
    'GTTGGTGGPPPPPPGPTGGGWWGG',
    'GGGBBBGGPPFFFPGPGBBBWWWG',
    'GGGBBBGGPPFFFPGPGBBBGWGG',
    'TGGBBBGGPPFFFPGPGBBBGGGG',
    'GGGGDGGGPPPPPPGPGGDGGGGG',
    'GGPPPPPPPPPSPPPPPPPPPPGG',
    'GGGGGGGGPGGGGGGPGGGGGGGG',
    'GWWGGGGGPTGGGGGPGGGGGGTG',
    'GWWBBBGGPGBBBGGPGBBBGGGG',
    'GWGBBBGGPGBBBGGPGBBBGGGG',
    'GGGBBBGGPGBBBGGPGBBBGGTT',
    'GGGGDGGGPGGDGGGPGGDGGGGG',
    'GGPPPPPPPPPPPPPPPPPPPPGG',
    'TGGGGGGGGGGGGGGGGGGGGGGT',
    'TTGGGTGGGGGGTGGGTGTGGGTT',
  ];

  /// dungeonId → 건물 점유 영역의 왼쪽 위(최소 col,row) 타일.
  static const Map<String, (int, int)> buildings = {
    'k8s': (3, 3),
    'jvm': (10, 3),
    'db': (17, 3),
    'net': (3, 10),
    'build': (17, 10),
    'ops': (3, 17),
    'ai': (10, 17),
    'shell': (17, 17),
  };

  /// dungeonId → 문 타일.
  static const Map<String, (int, int)> doors = {
    'k8s': (4, 6),
    'jvm': (11, 6),
    'db': (18, 6),
    'net': (4, 13),
    'build': (18, 13),
    'ops': (4, 20),
    'ai': (11, 20),
    'shell': (18, 20),
  };

  static const (int, int) startTile = (11, 14);
  static const (int, int) fountainTile = (11, 11);

  /// 범위 밖이면 null.
  static String? tileAt(int col, int row) {
    if (col < 0 || row < 0 || col >= size || row >= size) return null;
    return rows[row][col];
  }

  static bool isWalkable(int col, int row) => switch (tileAt(col, row)) {
        'G' || 'P' || 'D' || 'S' => true,
        _ => false,
      };

  /// 문 타일이면 그 dungeonId.
  static String? doorAt(int col, int row) {
    for (final e in doors.entries) {
      if (e.value == (col, row)) return e.key;
    }
    return null;
  }

  /// 건물 점유 타일이면 그 dungeonId.
  static String? buildingAt(int col, int row) {
    for (final e in buildings.entries) {
      final (c0, r0) = e.value;
      if (col >= c0 &&
          col < c0 + footprint &&
          row >= r0 &&
          row < r0 + footprint) {
        return e.key;
      }
    }
    return null;
  }
}
