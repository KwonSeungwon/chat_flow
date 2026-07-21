import 'package:intl/intl.dart';

/// Shared timestamp formatting helpers.
///
/// Two relative-time variants exist in the codebase:
///   1. [relativeTime] — full version (with "N일 전" tier and yyyy-MM-dd
///      fallback). Used by mentions_screen.
///   2. [relativeTimeCompact] — compact version (no "days ago" tier,
///      M/d fallback). Used by moderator_queue_sheet.
///
/// Both share the same "just now / minutes / hours" thresholds.

/// Full relative time: 방금 / N분 전 / N시간 전 / N일 전 / yyyy-MM-dd.
String relativeTime(DateTime dt, {DateTime? now}) {
  final ref = now ?? DateTime.now();
  final diff = ref.difference(dt);
  if (diff.inMinutes < 1) return '방금';
  if (diff.inHours < 1) return '${diff.inMinutes}분 전';
  if (diff.inDays < 1) return '${diff.inHours}시간 전';
  if (diff.inDays < 7) return '${diff.inDays}일 전';
  return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
}

/// Compact relative time: 방금 / N분 전 / N시간 전 / M/d.
String relativeTimeCompact(DateTime dt, {DateTime? now}) {
  final ref = now ?? DateTime.now();
  final diff = ref.difference(dt);
  if (diff.inMinutes < 1) return '방금';
  if (diff.inMinutes < 60) return '${diff.inMinutes}분 전';
  if (diff.inHours < 24) return '${diff.inHours}시간 전';
  return '${dt.month}/${dt.day}';
}

/// Format a DateTime as HH:mm (24-hour, zero-padded).
String hhmm(DateTime dt) => DateFormat('HH:mm').format(dt);
