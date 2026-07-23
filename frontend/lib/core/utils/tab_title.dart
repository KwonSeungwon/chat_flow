// Conditional import: web → tab_title_web.dart, otherwise stub.
export 'tab_title_stub.dart'
    if (dart.library.html) 'tab_title_web.dart';

/// Format the browser tab title based on unread message count.
/// Pure function — safe to call on any platform.
String formatTabTitle(int unreadTotal) {
  if (unreadTotal <= 0) return 'ChatFlow';
  if (unreadTotal > 99) return '(99+) ChatFlow';
  return '($unreadTotal) ChatFlow';
}
