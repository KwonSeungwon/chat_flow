import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/dio_client.dart';
import '../../../shared/widgets/user_avatar.dart';
import '../profile_provider.dart';

Future<void> showProfileEditDialog(BuildContext context) {
  final isMobile = MediaQuery.of(context).size.width < 600;
  if (isMobile) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: const _ProfileEditBody(),
      ),
    );
  }
  return showDialog(
    context: context,
    builder: (_) => const Dialog(
      child: SizedBox(width: 480, child: _ProfileEditBody()),
    ),
  );
}

class _ProfileEditBody extends ConsumerStatefulWidget {
  const _ProfileEditBody();

  @override
  ConsumerState<_ProfileEditBody> createState() => _ProfileEditBodyState();
}

class _ProfileEditBodyState extends ConsumerState<_ProfileEditBody> {
  final _statusCtrl = TextEditingController();
  final _bioCtrl = TextEditingController();
  String? _avatarUrl;
  bool _initialized = false;
  bool _saving = false;
  bool _uploading = false;

  @override
  void dispose() {
    _statusCtrl.dispose();
    _bioCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final profileAsync = ref.watch(profileProvider);

    return profileAsync.when(
      loading: () => const Padding(
          padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator())),
      error: (e, _) => Padding(
          padding: const EdgeInsets.all(24),
          child: Text('프로필을 불러오지 못했습니다.\n$e')),
      data: (profile) {
        if (!_initialized) {
          _statusCtrl.text = profile.statusMessage ?? '';
          _bioCtrl.text = profile.bio ?? '';
          _avatarUrl = profile.profileImageUrl;
          _initialized = true;
        }
        return Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.account_circle_outlined),
                  const SizedBox(width: 8),
                  const Text('프로필 편집',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.close, size: 20),
                    onPressed: _saving ? null : () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Center(
                child: Stack(
                  children: [
                    UserAvatar(
                      fallbackName: profile.username,
                      imageUrl: _avatarUrl,
                      radius: 48,
                    ),
                    Positioned(
                      right: -6, bottom: -6,
                      child: Material(
                        color: Theme.of(context).colorScheme.primary,
                        shape: const CircleBorder(),
                        child: InkWell(
                          customBorder: const CircleBorder(),
                          onTap: _uploading ? null : _pickAvatar,
                          child: Padding(
                            padding: const EdgeInsets.all(8),
                            child: _uploading
                                ? const SizedBox(
                                    width: 16, height: 16,
                                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                                : const Icon(Icons.camera_alt, size: 16, color: Colors.white),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Text(profile.username,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 16),
              TextField(
                controller: _statusCtrl,
                maxLength: 100,
                decoration: const InputDecoration(
                  labelText: '상태 메시지',
                  hintText: '예: 회의 중',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _bioCtrl,
                maxLength: 300,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: '한 줄 소개',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: _saving ? null : () => Navigator.of(context).pop(),
                    child: const Text('취소'),
                  ),
                  const SizedBox(width: 4),
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    child: _saving
                        ? const SizedBox(
                            width: 16, height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : const Text('저장'),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _pickAvatar() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['jpg', 'jpeg', 'png', 'gif', 'webp'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.first;
    final bytes = file.bytes;
    if (bytes == null) return;

    setState(() => _uploading = true);
    try {
      final dioClient = ref.read(dioClientProvider);
      final mime = _guessMime(file.extension ?? 'jpg');
      final resp = await dioClient.uploadFile(
        fileName: file.name,
        bytes: bytes,
        mimeType: mime,
      );
      final url = resp['fileUrl']?.toString();
      if (url != null && mounted) {
        setState(() => _avatarUrl = url);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('업로드 실패: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      final notifier = ref.read(profileProvider.notifier);
      await notifier.update(
        // 빈 문자열 = 명시적 비우기 (백엔드에서 NULL 저장)
        profileImageUrl: _avatarUrl ?? '',
        statusMessage: _statusCtrl.text,
        bio: _bioCtrl.text,
      );
      if (mounted) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('프로필이 저장되었습니다.'), duration: Duration(seconds: 2)),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _saving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('저장 실패: $e')),
        );
      }
    }
  }

  String _guessMime(String ext) {
    switch (ext.toLowerCase()) {
      case 'jpg': case 'jpeg': return 'image/jpeg';
      case 'png': return 'image/png';
      case 'gif': return 'image/gif';
      case 'webp': return 'image/webp';
      default: return 'application/octet-stream';
    }
  }
}
