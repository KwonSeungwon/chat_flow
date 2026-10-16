/// 서버 시각 문자열을 로컬 DateTime으로 파싱한다. 파싱 불가(null/빈 값/쓰레기)면 null.
///
/// 서버는 LocalDateTime(타임존 없음)을 UTC 기준으로 내려주므로, offset이 없는
/// 문자열은 UTC로 간주(`Z`를 붙임)해야 `.toLocal()`이 올바르게 변환된다.
/// offset(`Z`, `+09:00`)이 있으면 그대로 존중한다. Jackson 배열 형식
/// `[2026, 4, 13, 12, 30, 45, 123456789]`도 UTC로 처리한다.
DateTime? parseServerDateTime(Object? raw) {
  if (raw == null) return null;
  final s = raw.toString().trim();
  if (s.isEmpty) return null;

  if (s.startsWith('[')) {
    try {
      final parts = s
          .replaceAll(RegExp(r'[\[\]\s]'), '')
          .split(',')
          .map(int.parse)
          .toList();
      if (parts.length < 6) return null;
      final ms = parts.length >= 7 ? parts[6] ~/ 1000000 : 0;
      return DateTime.utc(
              parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], ms)
          .toLocal();
    } catch (_) {
      return null;
    }
  }

  final hasOffset = s.endsWith('Z') ||
      RegExp(r'[T ]\d{2}:\d{2}.*[+-]\d{2}(:?\d{2})?$').hasMatch(s);
  return DateTime.tryParse(hasOffset ? s : '${s}Z')?.toLocal();
}
