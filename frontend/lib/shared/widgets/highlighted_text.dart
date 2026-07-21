import 'package:flutter/material.dart';

/// [text]에서 [query]와 일치하는 모든 구간(대소문자 무시)을 강조해 렌더한다.
///
/// 핵심: 기본 스타일을 `Text.rich`의 `style`(inherit: true)로 넘겨, 이 위젯이
/// 실제로 그려지는 위치의 주변 `DefaultTextStyle`과 렌더 시점에 병합되게 한다.
/// 이렇게 하지 않고 `DefaultTextStyle.of(context)`를 다른(상위) 컨텍스트에서
/// 스냅샷해 루트 span 스타일로 쓰면, Scaffold/Material 바깥에서는 WidgetsApp의
/// "빨간 밑줄" 폴백 에러 스타일이 잡혀 본문 전체가 빨간 밑줄로 깨진다.
class HighlightedText extends StatelessWidget {
  final String text;
  final String query;
  final double fontSize;
  final int maxLines;

  const HighlightedText({
    super.key,
    required this.text,
    required this.query,
    this.fontSize = 14,
    this.maxLines = 3,
  });

  @override
  Widget build(BuildContext context) {
    final baseStyle = TextStyle(fontSize: fontSize);

    if (query.isEmpty) {
      return Text(
        text,
        style: baseStyle,
        maxLines: maxLines,
        overflow: TextOverflow.ellipsis,
      );
    }

    final lowerText = text.toLowerCase();
    final lowerQuery = query.toLowerCase();
    final spans = <TextSpan>[];
    int start = 0;

    while (true) {
      final idx = lowerText.indexOf(lowerQuery, start);
      if (idx == -1) {
        spans.add(TextSpan(text: text.substring(start)));
        break;
      }
      if (idx > start) {
        spans.add(TextSpan(text: text.substring(start, idx)));
      }
      spans.add(TextSpan(
        text: text.substring(idx, idx + lowerQuery.length),
        style: TextStyle(
          backgroundColor: Theme.of(context).colorScheme.primaryContainer,
          fontWeight: FontWeight.bold,
        ),
      ));
      start = idx + lowerQuery.length;
    }

    return Text.rich(
      TextSpan(children: spans),
      style: baseStyle,
      maxLines: maxLines,
      overflow: TextOverflow.ellipsis,
    );
  }
}
