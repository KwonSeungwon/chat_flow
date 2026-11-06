import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// 카카오·네이버·구글 로그인 버튼. 누르면 gateway의 인가 요청 시작 주소를 [onStart]에 넘긴다.
///
/// 색과 로고는 각 제공자의 버튼 가이드를 따른다.
/// - 카카오: 배경 #FEE500, 검은 말풍선 심볼, 글자 검정 85%
/// - 네이버: 배경 #03C75A, 흰 N 로고, 글자 흰색
/// - 구글: 흰 배경, 테두리 #747775, 4색 G 로고, 글자 #1F1F1F, "Google 계정으로 계속하기"
class SocialLoginButtons extends StatelessWidget {
  final void Function(String path) onStart;

  const SocialLoginButtons({super.key, required this.onStart});

  static const _kakaoSymbol =
      '<svg viewBox="0 0 18 18"><path fill="#000000" d="M9 1.5C4.58 1.5 1 4.3 1 7.75c0 2.2 1.46 4.13 3.67 5.25l-.75 2.76c-.07.24.2.43.41.29l3.3-2.18c.45.05.9.08 1.37.08 4.42 0 8-2.8 8-6.25S13.42 1.5 9 1.5z"/></svg>';
  static const _naverLogo =
      '<svg viewBox="0 0 20 20"><path fill="#FFFFFF" d="M13.56 10.7 6.17 0H0v20h6.44V9.3L13.83 20H20V0h-6.44z"/></svg>';
  static const _googleG =
      '<svg viewBox="0 0 48 48"><path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/><path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/><path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/><path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/></svg>';

  // 로고 크기: 카카오 심볼·구글 G는 18px, 네이버 N은 글자형 로고라 같은 높이에서 시각 크기를 맞추려 14px.
  static const _providers = [
    ('kakao', '카카오로 시작하기', _kakaoSymbol, 18.0, Color(0xFFFEE500), Color(0xD9000000), Color(0xFFFEE500)),
    ('naver', '네이버로 시작하기', _naverLogo, 14.0, Color(0xFF03C75A), Colors.white, Color(0xFF03C75A)),
    ('google', 'Google 계정으로 계속하기', _googleG, 18.0, Colors.white, Color(0xFF1F1F1F), Color(0xFF747775)),
  ];

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (id, label, svg, size, bg, fg, border) in _providers) ...[
          SizedBox(
            height: 44,
            child: OutlinedButton(
              onPressed: () => onStart('/oauth2/authorization/$id'),
              style: OutlinedButton.styleFrom(
                backgroundColor: bg,
                foregroundColor: fg,
                side: BorderSide(color: border),
                padding: const EdgeInsets.symmetric(horizontal: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              // 로고는 왼쪽, 글자는 가운데. 로고-글자 간격 10px을 보장하고 오른쪽에 로고 폭만큼 비워 글자를 가운데 맞춘다.
              child: Row(
                children: [
                  SizedBox(
                    width: 20,
                    height: 20,
                    child: Center(
                      child: SvgPicture.string(svg,
                          key: ValueKey('social-logo-$id'), width: size, height: size),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(label,
                        textAlign: TextAlign.center,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                  ),
                  const SizedBox(width: 30),
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
        ],
      ],
    );
  }
}
