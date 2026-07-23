/// Format the browser tab title based on unread message count.
/// Pure function — safe to call on any platform. The web document.title is
/// owned by MaterialApp.title (Flutter's Title widget), which consumes this.
String formatTabTitle(int unreadTotal) {
  if (unreadTotal <= 0) return 'ChatFlow';
  if (unreadTotal > 99) return '(99+) ChatFlow';
  return '($unreadTotal) ChatFlow';
}
