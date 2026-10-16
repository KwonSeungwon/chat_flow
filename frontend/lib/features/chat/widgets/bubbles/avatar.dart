import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';

/// Deterministic avatar colour derived from a username.
Color avatarColor(String name) =>
    AppColors.avatarPalette[name.hashCode.abs() % AppColors.avatarPalette.length];

/// 카카오톡형 스쿼클 아바타 — 이름 첫 글자를 보여준다.
class Avatar extends StatelessWidget {
  /// 상대 메시지 그룹의 연속 줄은 이 폭만큼 비워 정렬을 맞춘다.
  static const double size = 40;

  final String name;
  final Color color;
  const Avatar({super.key, required this.name, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: ShapeDecoration(
        // ContinuousRectangleBorder는 반경이 원형 모서리의 약 2.3배일 때
        // 비슷한 크기로 보인다 — 32 ≈ 원형 반경 14의 스쿼클.
        shape: ContinuousRectangleBorder(borderRadius: BorderRadius.circular(32)),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color.lerp(color, Colors.white, 0.25)!, color],
        ),
      ),
      child: Center(
        child: Text(
          name.isNotEmpty ? name[0].toUpperCase() : '?',
          style: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}
