import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/constants/chat_strings.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/theme/font_scale_provider.dart';
import '../../../core/utils/url_helper.dart';
import '../../auth/auth_provider.dart';
import '../dialogs/change_password_dialog.dart';
import 'bookmarks_dialog.dart';

/// Reusable profile avatar widget used by both [showProfileDialog] and [ChatPage].
class ProfileAvatar extends StatelessWidget {
  final String? url;
  final double radius;
  const ProfileAvatar({super.key, required this.url, required this.radius});

  @override
  Widget build(BuildContext context) {
    return CircleAvatar(
      radius: radius,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
      child: ClipOval(
        child: (url != null && url!.isNotEmpty)
            ? Image.network(
                url!,
                width: radius * 2,
                height: radius * 2,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    Icon(Icons.person, size: radius),
              )
            : Icon(Icons.person, size: radius),
      ),
    );
  }
}

Future<void> _changeProfileImage(BuildContext context, WidgetRef ref) async {
  try {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['jpg', 'jpeg', 'png', 'gif', 'webp'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.first;
    final bytes = file.bytes;
    if (bytes == null) return;

    final ext = file.extension?.toLowerCase() ?? 'jpg';
    const mimeMap = {'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'png': 'image/png', 'gif': 'image/gif', 'webp': 'image/webp'};
    final mimeType = mimeMap[ext] ?? 'image/jpeg';

    final dioClient = ref.read(dioClientProvider);
    final uploadResult = await dioClient.uploadFile(fileName: file.name, bytes: bytes, mimeType: mimeType);
    final fileUrl = uploadResult['fileUrl']?.toString() ?? '';
    if (fileUrl.isNotEmpty) {
      await ref.read(authProvider.notifier).updateProfileImage(fileUrl);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text(ChatStrings.profileImageChanged)));
      }
    }
  } catch (_) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('프로필 이미지 변경에 실패했습니다.')));
    }
  }
}

void showProfileDialog(BuildContext context, WidgetRef ref) {
  final auth = ref.read(authProvider);
  showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('프로필 관리'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ProfileAvatar(
            url: auth.profileImageUrl != null ? buildFullUrl(auth.profileImageUrl!) : null,
            radius: 40,
          ),
          const SizedBox(height: 12),
          Text(auth.username.isNotEmpty ? auth.username : '사용자',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
          const SizedBox(height: 4),
          Text(auth.role, style: TextStyle(fontSize: 14, color: Theme.of(ctx).colorScheme.onSurfaceVariant)),
          const SizedBox(height: 4),
          if (auth.userId != null)
            Text('ID: ${auth.userId}', style: TextStyle(fontSize: 11, color: Theme.of(ctx).colorScheme.onSurfaceVariant.withAlpha(120))),
          const SizedBox(height: 16),
          // Font scale selector
          Align(
            alignment: Alignment.centerLeft,
            child: Text('글꼴 크기', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Theme.of(ctx).colorScheme.onSurfaceVariant)),
          ),
          const SizedBox(height: 6),
          Consumer(builder: (_, ref, __) {
            final current = ref.watch(fontScaleProvider);
            return SegmentedButton<FontScale>(
              segments: FontScale.values
                  .map((e) => ButtonSegment(value: e, label: Text(e.label)))
                  .toList(),
              selected: {current},
              onSelectionChanged: (set) =>
                  ref.read(fontScaleProvider.notifier).set(set.first),
              style: SegmentedButton.styleFrom(
                textStyle: const TextStyle(fontSize: 13),
              ),
            );
          }),
        ],
      ),
      actionsAlignment: MainAxisAlignment.center,
      actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
      actions: [
        Row(children: [
          Expanded(child: OutlinedButton.icon(
            icon: const Icon(Icons.camera_alt_outlined, size: 18),
            label: const Text('이미지 변경'),
            onPressed: () async {
              Navigator.of(ctx).pop();
              await _changeProfileImage(context, ref);
            },
          )),
          const SizedBox(width: 8),
          Expanded(child: OutlinedButton.icon(
            icon: const Icon(Icons.lock_outline, size: 18),
            label: const Text('비밀번호'),
            onPressed: () {
              Navigator.of(ctx).pop();
              showDialog(
                context: context,
                barrierDismissible: false,
                builder: (_) => const ChangePasswordDialog(),
              );
            },
          )),
        ]),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton.icon(
            icon: const Icon(Icons.bookmark_outline, size: 18),
            label: const Text('북마크'),
            onPressed: () {
              Navigator.of(ctx).pop();
              showBookmarksDialog(context, ref);
            },
          ),
        ),
      ],
    ),
  );
}
