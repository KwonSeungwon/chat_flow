import 'package:flutter/material.dart';

/// 카카오·네이버·구글 로그인 버튼. 누르면 gateway의 인가 요청 시작 주소를 [onStart]에 넘긴다.
class SocialLoginButtons extends StatelessWidget {
  final void Function(String path) onStart;

  const SocialLoginButtons({super.key, required this.onStart});

  static const _providers = [
    ('kakao', '카카오로 시작하기', Color(0xFFFEE500), Color(0xD9000000)),
    ('naver', '네이버로 시작하기', Color(0xFF03C75A), Colors.white),
    ('google', 'Google로 시작하기', Colors.white, Color(0xFF1F1F1F)),
  ];

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (id, label, bg, fg) in _providers) ...[
          SizedBox(
            height: 44,
            child: OutlinedButton(
              onPressed: () => onStart('/oauth2/authorization/$id'),
              style: OutlinedButton.styleFrom(
                backgroundColor: bg,
                foregroundColor: fg,
                side: BorderSide(color: id == 'google' ? const Color(0xFFDADCE0) : bg),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              child: Text(label, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
            ),
          ),
          const SizedBox(height: 8),
        ],
      ],
    );
  }
}
