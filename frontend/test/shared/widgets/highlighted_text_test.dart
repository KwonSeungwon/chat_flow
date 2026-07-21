import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/widgets/highlighted_text.dart';

/// Regression guard for the "빨간 밑줄" search-result bug: the highlighted text
/// must inherit the ambient DefaultTextStyle color at its render location, never
/// the WidgetsApp red error-fallback (Color(0xFFFF0000) + underline).
const _sentinel = Color(0xFF00C853); // distinctive green

Widget _wrap(Widget child) => MaterialApp(
      home: Scaffold(
        body: DefaultTextStyle(
          style: const TextStyle(color: _sentinel),
          child: child,
        ),
      ),
    );

RichText _richTextOf(WidgetTester tester) =>
    tester.widget<RichText>(find.byType(RichText).first);

/// Text.rich은 전달한 TextSpan을 루트 스타일 span의 자식으로 한 겹 감싼다.
/// 실제 텍스트를 가진 leaf span만 평탄화해 돌려준다.
List<TextSpan> _leafSpans(InlineSpan root) {
  final out = <TextSpan>[];
  void visit(InlineSpan s) {
    if (s is TextSpan) {
      if (s.text != null) out.add(s);
      s.children?.forEach(visit);
    }
  }

  visit(root);
  return out;
}

void main() {
  group('HighlightedText', () {
    testWidgets('본문은 주변 DefaultTextStyle 색을 상속한다 (빨간 폴백 아님)',
        (tester) async {
      await tester.pumpWidget(_wrap(
        const HighlightedText(text: '안녕하세요 테스트 메시지입니다', query: '테스트'),
      ));

      final root = _richTextOf(tester).text as TextSpan;
      // 루트 스타일 = DefaultTextStyle(green) merge fontSize → 색은 green
      expect(root.style?.color, _sentinel);
      expect(root.style?.fontSize, 14);
      // 폴백 에러 스타일 특징(빨강/밑줄)이 없어야 한다
      expect(root.style?.decoration, isNot(TextDecoration.underline));
      expect(root.style?.color, isNot(const Color(0xFFFF0000)));
    });

    testWidgets('일치 구간은 굵게 + primaryContainer 배경으로 강조된다', (tester) async {
      await tester.pumpWidget(_wrap(
        const HighlightedText(text: 'hello world', query: 'world'),
      ));

      final leaves = _leafSpans(_richTextOf(tester).text);
      final highlighted =
          leaves.firstWhere((s) => s.style?.fontWeight == FontWeight.bold);
      expect(highlighted.text, 'world');
      expect(highlighted.style?.backgroundColor, isNotNull);

      // 비강조 구간은 별도 색/밑줄을 강제하지 않는다 (루트 상속)
      final plain = leaves.firstWhere((s) => s.text == 'hello ');
      expect(plain.style?.decoration, isNull);
      expect(plain.style?.color, isNull);
    });

    testWidgets('대소문자 무시하고 모든 일치 구간을 강조한다', (tester) async {
      await tester.pumpWidget(_wrap(
        const HighlightedText(text: 'AaA', query: 'a'),
      ));

      final bold = _leafSpans(_richTextOf(tester).text)
          .where((s) => s.style?.fontWeight == FontWeight.bold)
          .toList();
      expect(bold.length, 3); // A, a, A 세 글자 모두 강조
    });

    testWidgets('query가 비면 강조 없이 평문으로 렌더한다', (tester) async {
      await tester.pumpWidget(_wrap(
        const HighlightedText(text: '검색어 없음', query: ''),
      ));

      expect(find.text('검색어 없음'), findsOneWidget);
      final root = _richTextOf(tester).text as TextSpan;
      // 자식 span 없이 단일 텍스트
      expect(root.children, anyOf(isNull, isEmpty));
      expect(root.style?.color, _sentinel);
    });
  });
}
