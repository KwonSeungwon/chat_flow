import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/dio_client.dart';
import '../../shared/models/user_profile.dart';
import 'profile_api.dart';

final profileApiProvider = Provider<ProfileApi>((ref) {
  final dioClient = ref.watch(dioClientProvider);
  return ProfileApi(dioClient.dio);
});

class ProfileNotifier extends StateNotifier<AsyncValue<UserProfile>> {
  final ProfileApi _api;

  ProfileNotifier(this._api) : super(const AsyncValue.loading()) {
    fetch();
  }

  Future<void> fetch() async {
    try {
      final profile = await _api.getMe();
      if (!mounted) return;
      state = AsyncValue.data(profile);
    } catch (e, st) {
      debugPrint('[ProfileNotifier] fetch error: $e');
      if (!mounted) return;
      state = AsyncValue.error(e, st);
    }
  }

  Future<void> update({
    String? profileImageUrl,
    String? statusMessage,
    String? bio,
  }) async {
    final updated = await _api.updateMe(
      profileImageUrl: profileImageUrl,
      statusMessage: statusMessage,
      bio: bio,
    );
    if (!mounted) return;
    state = AsyncValue.data(updated);
  }
}

final profileProvider =
    StateNotifierProvider<ProfileNotifier, AsyncValue<UserProfile>>((ref) {
  final api = ref.watch(profileApiProvider);
  return ProfileNotifier(api);
});

/// 다른 사용자 프로필 조회 (캐시) — 멤버 미리보기용.
final userProfileByIdProvider =
    FutureProvider.family<UserProfile, String>((ref, userId) async {
  final api = ref.watch(profileApiProvider);
  return api.getById(userId);
});
