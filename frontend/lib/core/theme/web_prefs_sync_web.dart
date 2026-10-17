// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;

/// Raw localStorage value, where shared_preferences_web keeps its entries.
String? readWebPrefsRaw(String key) {
  try {
    return html.window.localStorage[key];
  } catch (_) {
    return null; // storage blocked
  }
}
