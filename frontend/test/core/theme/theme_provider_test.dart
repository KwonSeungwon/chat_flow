import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:gossiplog/core/theme/theme_provider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('저장된 선택이 없으면 기본 테마는 라이트다', () async {
    SharedPreferences.setMockInitialValues({});
    final notifier = ThemeModeNotifier();
    expect(notifier.state, ThemeMode.light);
    await Future<void>.delayed(Duration.zero); // _load 완료
    expect(notifier.state, ThemeMode.light);
  });

  test('저장된 다크 선택은 그대로 존중한다', () async {
    SharedPreferences.setMockInitialValues({'gossiplog-theme-mode': 'dark'});
    final notifier = ThemeModeNotifier();
    await Future<void>.delayed(Duration.zero);
    expect(notifier.state, ThemeMode.dark);
  });

  test('알 수 없는 저장값은 라이트로 떨어진다', () async {
    SharedPreferences.setMockInitialValues({'gossiplog-theme-mode': 'neon'});
    final notifier = ThemeModeNotifier();
    await Future<void>.delayed(Duration.zero);
    expect(notifier.state, ThemeMode.light);
  });

  test('동기 소스에 다크가 있으면 첫 프레임부터 다크다', () async {
    SharedPreferences.setMockInitialValues({'gossiplog-theme-mode': 'dark'});
    final notifier = ThemeModeNotifier(readStoredSync: (_) => '"dark"');
    expect(notifier.state, ThemeMode.dark); // await 전
    await Future<void>.delayed(Duration.zero);
    expect(notifier.state, ThemeMode.dark);
  });

  test('동기 소스는 웹 저장 키(flutter. 접두)를 JSON 문자열로 읽는다', () {
    String? asked;
    final notifier = ThemeModeNotifier(readStoredSync: (key) {
      asked = key;
      return '"dark"';
    });
    expect(asked, 'flutter.gossiplog-theme-mode');
    expect(notifier.state, ThemeMode.dark);
  });

  test('동기 소스 값이 깨졌으면 라이트로 시작한다', () {
    SharedPreferences.setMockInitialValues({});
    expect(ThemeModeNotifier(readStoredSync: (_) => 'not json').state,
        ThemeMode.light);
    expect(ThemeModeNotifier(readStoredSync: (_) => null).state,
        ThemeMode.light);
  });
}
