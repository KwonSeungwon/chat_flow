import 'package:chatflow/features/chat/helpers/mention_spans.dart';
import 'package:flutter_test/flutter_test.dart';

/// `@name` substrings that would be highlighted, in order.
List<String> highlighted(String content, {String? me}) =>
    findMentionSpans(content, me: me)
        .map((s) => content.substring(s.start, s.end))
        .toList();

List<String> highlightedAsMe(String content, {String? me}) =>
    findMentionSpans(content, me: me)
        .where((s) => s.isMe)
        .map((s) => content.substring(s.start, s.end))
        .toList();

void main() {
  group('findMentionSpans — general', () {
    test('finds ascii, Hangul, dotted and underscored names', () {
      expect(highlighted('hi @bob and @김간호사, also @under_score and @phill.park'),
          ['@bob', '@김간호사', '@under_score', '@phill.park']);
    });

    test('returns nothing when there is no @', () {
      expect(findMentionSpans('점심 뭐 먹지'), isEmpty);
      expect(findMentionSpans(''), isEmpty);
    });

    test('an email address is not a mention', () {
      expect(highlighted('mail me at bob@phill.park thanks'), isEmpty);
    });

    test('a bare @ highlights nothing', () {
      expect(highlighted('what @ even'), isEmpty);
      expect(highlighted('@'), isEmpty);
    });

    test('trailing punctuation stays out of the highlight', () {
      expect(highlighted('@bob, @김간호사! @phill.park.'),
          ['@bob', '@김간호사', '@phill.park']);
    });

    test('leading punctuation cannot open a name', () {
      // "." and "-" extend a name but must not start one.
      expect(highlighted('@.foo bar'), isEmpty);
      expect(highlighted('@-foo bar'), isEmpty);
    });

    test('reports ranges that index back into the source string', () {
      const content = 'ping @bob now';
      final span = findMentionSpans(content).single;
      expect(content.substring(span.start, span.end), '@bob');
      expect(span.isMe, isFalse);
    });
  });

  group('findMentionSpans — the signed-in user', () {
    test('matches a username with a space, which no character class can', () {
      // The old regex highlighted "@Phill" and left " Park" as body text.
      expect(highlightedAsMe('hey @Phill Park are you there', me: 'Phill Park'),
          ['@Phill Park']);
    });

    test('matches the 36-char hyphenated userId fallback username', () {
      const uuid = 'bd515969-a57e-4cc1-b72d-6b2508b483d0';
      expect(highlightedAsMe('@$uuid 확인 부탁', me: uuid), ['@$uuid']);
    });

    test('matches a name longer than thirty characters', () {
      final long = 'a' * 45;
      expect(highlightedAsMe('@$long hi', me: long), ['@$long']);
    });

    test('matches an accented name', () {
      expect(highlightedAsMe('@José hi', me: 'José'), ['@José']);
    });

    test('someone else being mentioned is not flagged as me', () {
      expect(highlightedAsMe('@alice hello', me: 'bob'), isEmpty);
      expect(highlighted('@alice hello', me: 'bob'), ['@alice']);
    });

    test('a same-length different name is not flagged as me', () {
      // Guards the range arithmetic: "dan" and "bob" are both 3 chars.
      expect(highlightedAsMe('@dan hi', me: 'bob'), isEmpty);
    });

    test('is case sensitive, like the server-side lookup', () {
      expect(highlightedAsMe('@BOB hi', me: 'bob'), isEmpty);
    });

    test('a longer name that merely starts with mine is not me', () {
      expect(highlightedAsMe('@bobby hi', me: 'bob'), isEmpty);
      expect(highlighted('@bobby hi', me: 'bob'), ['@bobby']);
    });

    test('a Korean particle glued to my name is not a mention', () {
      // The server cannot resolve "@김간호사님" either, so highlighting it
      // would promise a notification that never arrives.
      expect(highlightedAsMe('@김간호사님 안녕', me: '김간호사'), isEmpty);
    });

    test('an empty or absent username never matches', () {
      expect(highlightedAsMe('@bob hi', me: ''), isEmpty);
      expect(highlightedAsMe('@bob hi'), isEmpty);
    });

    test('mixes me and others in one message, in order', () {
      final spans = findMentionSpans('@alice @Phill Park @bob', me: 'Phill Park');
      expect(spans.map((s) => s.isMe), [false, true, false]);
    });

    test('a dotted longer name still flags me — the roster is not known here', () {
      // Documented limitation: with members "phill" and "phill.park" the server
      // resolves "@phill.park" to phill.park, but this helper has no roster to
      // do longest-match against, so it lights up for me="phill" too. isMe is a
      // hint; the badge and the push both come from the server.
      expect(highlightedAsMe('@phill.park hi', me: 'phill'), ['@phill']);
    });

    test('scanning resumes after my name rather than inside it', () {
      const me = 'a@b';
      expect(highlightedAsMe('@a@b done', me: me), ['@a@b']);
      expect(findMentionSpans('@a@b done', me: me), hasLength(1));
    });
  });
}
