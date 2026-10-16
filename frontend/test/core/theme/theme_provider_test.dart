import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:chatflow/core/theme/theme_provider.dart';

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
    SharedPreferences.setMockInitialValues({'chatflow-theme-mode': 'dark'});
    final notifier = ThemeModeNotifier();
    await Future<void>.delayed(Duration.zero);
    expect(notifier.state, ThemeMode.dark);
  });

  test('알 수 없는 저장값은 라이트로 떨어진다', () async {
    SharedPreferences.setMockInitialValues({'chatflow-theme-mode': 'neon'});
    final notifier = ThemeModeNotifier();
    await Future<void>.delayed(Duration.zero);
    expect(notifier.state, ThemeMode.light);
  });
}
