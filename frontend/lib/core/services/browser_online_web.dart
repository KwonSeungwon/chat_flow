// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;

Stream<void> browserOnlineEvents() => html.window.onOnline.map((_) {});
