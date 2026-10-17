import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'web_prefs_sync.dart';

const _themeKey = 'chatflow-theme-mode';

final themeModeProvider = StateNotifierProvider<ThemeModeNotifier, ThemeMode>(
  (ref) => ThemeModeNotifier(),
);

class ThemeModeNotifier extends StateNotifier<ThemeMode> {
  /// [readStoredSync] reads a raw stored value synchronously (web
  /// localStorage), so a saved dark choice is in place on the first frame.
  /// Elsewhere it returns null and the async load below applies it.
  ThemeModeNotifier({String? Function(String key) readStoredSync = readWebPrefsRaw})
      : super(_initial(readStoredSync)) {
    _load();
  }

  /// shared_preferences_web stores `flutter.<key>` as a JSON-encoded string.
  static ThemeMode _initial(String? Function(String key) readStoredSync) {
    try {
      final raw = readStoredSync('flutter.$_themeKey');
      if (raw == null) return ThemeMode.light;
      final value = jsonDecode(raw);
      return value is String ? _parse(value) : ThemeMode.light;
    } catch (_) {
      return ThemeMode.light;
    }
  }

  static ThemeMode _parse(String value) => ThemeMode.values.firstWhere(
        (e) => e.name == value,
        orElse: () => ThemeMode.light,
      );

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final value = prefs.getString(_themeKey);
    if (value != null) {
      state = _parse(value);
    }
  }

  Future<void> toggle() async {
    state = state == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_themeKey, state.name);
  }

  Future<void> setMode(ThemeMode mode) async {
    state = mode;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_themeKey, state.name);
  }
}
