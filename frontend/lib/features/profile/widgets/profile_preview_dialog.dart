import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/user_avatar.dart';
import '../profile_provider.dart';

Future<void> showProfilePreview(BuildContext context, String userId) {
  return showDialog(
    context: context,
    builder: (_) => Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360),
        child: _ProfilePreviewBody(userId: userId),
      ),
    ),
  );
}

class _ProfilePreviewBody extends ConsumerWidget {
  final String userId;
  const _ProfilePreviewBody({required this.userId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(userProfileByIdProvider(userId));

    return profileAsync.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(40),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 32, color: Colors.grey),
            const SizedBox(height: 8),
            Text('프로필을 불러오지 못했습니다.\n$e',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13)),
            const SizedBox(height: 8),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('닫기'),
            ),
          ],
        ),
      ),
      data: (profile) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            UserAvatar(
              fallbackName: profile.username,
              imageUrl: profile.profileImageUrl,
              radius: 40,
            ),
            const SizedBox(height: 12),
            Text(profile.username,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            if (profile.statusMessage != null && profile.statusMessage!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(profile.statusMessage!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, color: Colors.grey)),
            ],
            if (profile.bio != null && profile.bio!.isNotEmpty) ...[
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(profile.bio!, style: const TextStyle(fontSize: 13)),
              ),
            ],
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('닫기'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
