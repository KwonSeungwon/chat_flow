import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/dio_client.dart';
import '../../shared/models/user_profile.dart';
import '../auth/auth_provider.dart';
import 'profile_api.dart';

final profileApiProvider = Provider<ProfileApi>((ref) {
  final dioClient = ref.watch(dioClientProvider);
  return ProfileApi(dioClient.dio);
});

class ProfileNotifier extends StateNotifier<AsyncValue<UserProfile>> {
  final ProfileApi _api;
  final void Function(String? profileImageUrl)? _onUpdated;

  ProfileNotifier(this._api, {void Function(String? profileImageUrl)? onUpdated})
      : _onUpdated = onUpdated,
        super(const AsyncValue.loading()) {
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
    _onUpdated?.call(updated.profileImageUrl);
  }
}

final profileProvider =
    StateNotifierProvider.autoDispose<ProfileNotifier, AsyncValue<UserProfile>>(
        (ref) {
  // 계정이 바뀌면 이전 사용자의 프로필을 버리고 다시 가져온다.
  ref.watch(authProvider.select((a) => a.userId));
  final api = ref.watch(profileApiProvider);
  return ProfileNotifier(
    api,
    onUpdated: (url) {
      if (ref.read(authProvider).profileImageUrl != url) {
        ref.read(authProvider.notifier).applyProfileImage(url);
      }
    },
  );
});

/// 다른 사용자 프로필 조회 (캐시) — 멤버 미리보기용.
final userProfileByIdProvider =
    FutureProvider.family<UserProfile, String>((ref, userId) async {
  final api = ref.watch(profileApiProvider);
  return api.getById(userId);
});
