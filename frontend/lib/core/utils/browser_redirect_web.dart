// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;

/// 현재 탭을 [url]로 이동한다(소셜 로그인처럼 서버가 처리할 전체 페이지 이동).
void redirectBrowser(String url) => html.window.location.assign(url);
