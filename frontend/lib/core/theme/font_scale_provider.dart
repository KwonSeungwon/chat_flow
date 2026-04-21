import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

enum FontScale { small, medium, large }

extension FontScaleX on FontScale {
  double get factor => switch (this) {
    FontScale.small => 0.9,
    FontScale.medium => 1.0,
    FontScale.large => 1.15,
  };

  String get label => switch (this) {
    FontScale.small => '작게',
    FontScale.medium => '보통',
    FontScale.large => '크게',
  };
}

final fontScaleProvider =
    StateNotifierProvider<FontScaleNotifier, FontScale>((ref) {
  return FontScaleNotifier();
});

class FontScaleNotifier extends StateNotifier<FontScale> {
  static const _key = 'chatflow.fontScale';
  static const _storage = FlutterSecureStorage();

  FontScaleNotifier() : super(FontScale.medium) {
    _load();
  }

  Future<void> _load() async {
    final raw = await _storage.read(key: _key);
    if (raw == null) return;
    state = FontScale.values.firstWhere(
      (e) => e.name == raw,
      orElse: () => FontScale.medium,
    );
  }

  Future<void> set(FontScale scale) async {
    state = scale;
    await _storage.write(key: _key, value: scale.name);
  }
}
