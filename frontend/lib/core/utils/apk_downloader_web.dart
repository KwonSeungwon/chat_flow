// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;

void downloadApk(String url) {
  html.AnchorElement(href: url)
    ..setAttribute('download', 'chatflow-app.apk')
    ..click();
}
