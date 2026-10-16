import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../core/utils/url_helper.dart';

/// 메시지 버블 / 사이드바 / 멤버 시트 공통 avatar 위젯.
/// imageUrl 있으면 NetworkImage(상대 경로는 buildFullUrl로 풀어서),
/// 없거나 로드에 실패하면 첫 글자 + 사용자별 고유 색상으로 fallback.
class UserAvatar extends StatefulWidget {
  final String? imageUrl;
  final String fallbackName;
  final double radius;
  final VoidCallback? onTap;

  const UserAvatar({
    super.key,
    required this.fallbackName,
    this.imageUrl,
    this.radius = 18,
    this.onTap,
  });

  @override
  State<UserAvatar> createState() => _UserAvatarState();
}

class _UserAvatarState extends State<UserAvatar> {
  bool _loadFailed = false;

  @override
  void didUpdateWidget(UserAvatar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.imageUrl != widget.imageUrl) _loadFailed = false;
  }

  @override
  Widget build(BuildContext context) {
    final imageUrl = widget.imageUrl;
    final fallbackName = widget.fallbackName;
    final radius = widget.radius;
    final onTap = widget.onTap;
    final hasImage =
        !_loadFailed && imageUrl != null && imageUrl.trim().isNotEmpty;
    final color = AppColors.avatarPalette[
        fallbackName.hashCode.abs() % AppColors.avatarPalette.length];

    final avatar = CircleAvatar(
      radius: radius,
      backgroundColor: hasImage ? null : color,
      backgroundImage: hasImage ? NetworkImage(buildFullUrl(imageUrl)) : null,
      onBackgroundImageError: hasImage
          ? (_, __) {
              if (mounted) setState(() => _loadFailed = true);
            }
          : null,
      child: hasImage
          ? null
          : Text(
              fallbackName.isNotEmpty ? fallbackName.characters.first : '?',
              style: TextStyle(
                fontSize: radius * 0.85,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
    );

    if (onTap == null) return avatar;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(radius),
      child: avatar,
    );
  }
}
